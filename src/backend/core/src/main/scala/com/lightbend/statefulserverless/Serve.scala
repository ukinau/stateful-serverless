/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.statefulserverless

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import GrpcMarshalling.{marshalStream, unmarshalStream}
import akka.grpc.{Codecs, ProtobufSerializer, GrpcServiceException}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Segment
import akka.actor.{ActorRef, ActorSystem}
import akka.util.{ByteString, Timeout}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString}
import com.google.protobuf.empty.{EmptyProto => ProtobufEmptyProto}
import com.google.protobuf.any.{Any => ProtobufAny, AnyProto => ProtobufAnyProto}
import com.google.protobuf.Descriptors.{Descriptor, FieldDescriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{descriptor => ScalaPBDescriptorProtos}
import com.lightbend.statefulserverless.grpc._
import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import com.lightbend.statefulserverless.StateManager.CommandFailure
import io.grpc.Status


object Serve {
  // When the entity key is made up of multiple fields, this is used to separate them
  private final val EntityKeyValueSeparator = "-"
  private final val AnyTypeUrlHostName = "type.googleapis.com/"

  private final val NotFound: PartialFunction[HttpRequest, Future[HttpResponse]] = {
    case req: HttpRequest => Future.successful(HttpResponse(StatusCodes.NotFound))
  }

  private final object ReplySerializer extends ProtobufSerializer[ProtobufByteString] {
    override final def serialize(pbBytes: ProtobufByteString): ByteString =
      if (pbBytes.isEmpty) {
        ByteString.empty
      } else {
        ByteString.fromArrayUnsafe(pbBytes.toByteArray())
      }
    override final def deserialize(bytes: ByteString): ProtobufByteString =
      if (bytes.isEmpty) {
        ProtobufByteString.EMPTY
      } else {
        ProtobufByteString.readFrom(bytes.iterator.asInputStream)
      }
  }

  /**
    * ScalaPB doesn't do this conversion for us unfortunately.
    *
    * By doing it, we can use EntitykeyProto.entityKey.get() to read the entity key nicely.
    */
  private[this] final def convertFieldOptions(field: FieldDescriptor): ScalaPBDescriptorProtos.FieldOptions = {
    val fields =
      scalapb.UnknownFieldSet(field.getOptions.getUnknownFields.asMap.asScala.map {
        case (idx, f) => idx.toInt -> scalapb.UnknownFieldSet.Field(
          varint          = f.getVarintList.asScala.map(_.toLong),
          fixed64         = f.getFixed64List.asScala.map(_.toLong),
          fixed32         = f.getFixed32List.asScala.map(_.toInt),
          lengthDelimited = f.getLengthDelimitedList.asScala
        )
      }.toMap)

    ScalaPBDescriptorProtos.FieldOptions.fromJavaProto(field.toProto.getOptions).withUnknownFields(fields)
  }

  private final class CommandSerializer(commandName: String, desc: Descriptor) extends ProtobufSerializer[Command] {
    private[this] final val commandTypeUrl = AnyTypeUrlHostName + desc.getFullName
    private[this] final val extractId = {
      val fields = desc.getFields.iterator.asScala.
                     filter(field => EntitykeyProto.entityKey.get(convertFieldOptions(field))).
                     toArray.sortBy(_.getIndex)

      fields.length match {
        case 0 => throw new IllegalStateException(s"No field marked with [(com.lightbend.statefulserverless.grpc.entity_key) = true] found for $commandName")
        case 1 =>
          val f = fields.head
          (dm: DynamicMessage) => dm.getField(f).toString
        case _ =>
          (dm: DynamicMessage) => fields.iterator.map(dm.getField).mkString(EntityKeyValueSeparator)
      }
    }

    // Should not be used in practice
    override final def serialize(command: Command): ByteString = command.payload match {
      case None => ByteString.empty
      case Some(payload) => ByteString(payload.value.asReadOnlyByteBuffer())
    }

    override final def deserialize(bytes: ByteString): Command = {
      // Use of named parameters here is important, Command is a generated class and if the
      // order of fields changes, that could silently break this code
      // Note, we're not setting the command id. We'll leave it up to the StateManager actor
      // to generate an id that is unique per session.
      Command(entityId = extractId(DynamicMessage.parseFrom(desc, bytes.iterator.asInputStream)),
              name = commandName,
              payload = Some(ProtobufAny(typeUrl = commandTypeUrl, value = ProtobufByteString.copyFrom(bytes.asByteBuffer))))
    }
  }

  final class CommandMessageExtractor(shards: Int) extends HashCodeMessageExtractor(shards) {
    override final def entityId(message: Any): String = message match {
      case c: Command => c.entityId
    }
  }

  private final class gRPCMethod private[this](
    final val name: String,
    final val unmarshaller: ProtobufSerializer[Command],
    final val marshaller: ProtobufSerializer[ProtobufByteString]) {
    def this(method: MethodDescriptor) = this(method.getName, new CommandSerializer(method.getName, method.getInputType), ReplySerializer)
  }

  private[this] final def extractService(serviceName: String, descriptor: FileDescriptor): Option[ServiceDescriptor] = {
    val (pkg, name) = Names.splitPrev(serviceName)
    Some(descriptor).filter(_.getPackage == pkg).map(_.findServiceByName(name))
  }

  def createRoute(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, spec: EntitySpec)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
    descriptorSet.getFileList.iterator.asScala.map({
      fdp => FileDescriptor.buildFrom(fdp,
               Array( ScalaPBDescriptorProtos.DescriptorProtoCompanion.javaDescriptor,
                      EntitykeyProto.javaDescriptor,
                      ProtobufAnyProto.javaDescriptor,
                      ProtobufEmptyProto.javaDescriptor),
               true
             )
    }).map({
      descriptor => extractService(spec.serviceName, descriptor).map({
                      service =>
                       compileProxy(stateManager, proxyParallelism, relayTimeout, service) orElse
                       Reflection.serve(descriptor) orElse
                       NotFound
                    })
    }).collectFirst({ case Some(route) => route })
      .getOrElse(throw new Exception(s"Service ${spec.serviceName} not found in descriptors!"))
  }

  private[this] final def compileProxy(stateManager: ActorRef, proxyParallelism: Int, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val serviceName = serviceDesc.getFullName
    val rpcs = serviceDesc.getMethods.iterator.asScala.map(d => (Path / serviceName / d.getName, new gRPCMethod(d))).toMap
    val mapRequestFailureExceptions: (ActorSystem => PartialFunction[Throwable, Status]) = {
      val pf: PartialFunction[Throwable, Status] = {
        case CommandFailure(msg) => Status.UNKNOWN.augmentDescription(msg)
      }
      _ => pf
    }

    { case req: HttpRequest if rpcs.contains(req.uri.path) =>
        implicit val askTimeout = relayTimeout
        val responseCodec = Codecs.negotiate(req)
        val rpc = rpcs(req.uri.path)
        unmarshalStream(req)(rpc.unmarshaller, mat).
          map(_.mapAsync(proxyParallelism)(command => (stateManager ? command).mapTo[ProtobufByteString])).
          map(e => marshalStream(e, mapRequestFailureExceptions)(rpc.marshaller, mat, responseCodec, sys)).
          recoverWith(GrpcExceptionHandler.default(GrpcExceptionHandler.defaultMapper(sys)))
    }
  }
}

private[statefulserverless] object Names {
  final def splitPrev(name: String): (String, String) = {
    val dot = name.lastIndexOf('.')
    if (dot >= 0) {
      (name.substring(0, dot), name.substring(dot + 1))
    } else {
      ("", name)
    }
  }

  final def splitNext(name: String): (String, String) = {
    val dot = name.indexOf('.')
    if (dot >= 0) {
      (name.substring(0, dot), name.substring(dot + 1))
    } else {
      (name, "")
    }
  }
}

object Reflection {
  import _root_.grpc.reflection.v1alpha._

  private final val ReflectionPath = Path / ServerReflection.name / "ServerReflectionInfo"

  def serve(fileDesc: FileDescriptor)(implicit mat: Materializer, sys: ActorSystem): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    implicit val ec: ExecutionContext = mat.executionContext
    import ServerReflection.Serializers._

    val handler = handle(fileDesc)

    {
      case req: HttpRequest if req.uri.path == ReflectionPath =>
        val responseCodec = Codecs.negotiate(req)
        GrpcMarshalling.unmarshalStream(req)(ServerReflectionRequestSerializer, mat)
        .map(_ via handler)
        .map(e => GrpcMarshalling.marshalStream(e, GrpcExceptionHandler.defaultMapper)(ServerReflectionResponseSerializer, mat, responseCodec, sys))
    }
  }

  private final def findFileDescForName(name: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (name == fileDesc.getName) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForName(name, fd)).find(_.isDefined).flatten

  private final def containsSymbol(symbol: String, fileDesc: FileDescriptor): Boolean =
    (symbol.startsWith(fileDesc.getPackage)) && // Ensure package match first
    (Names.splitNext(if (fileDesc.getPackage.isEmpty) symbol else symbol.drop(fileDesc.getPackage.length + 1)) match {
      case ("", "") => false
      case (typeOrService, "") =>
      //fileDesc.findEnumTypeByName(typeOrService) != null || // TODO investigate if this is expected
        fileDesc.findMessageTypeByName(typeOrService) != null ||
        fileDesc.findServiceByName(typeOrService) != null
      case (service, method) =>
        Option(fileDesc.findServiceByName(service)).exists(_.findMethodByName(method) != null)
    })

  private final def findFileDescForSymbol(symbol: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsSymbol(symbol, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForSymbol(symbol, fd)).find(_.isDefined).flatten

  private final def containsExtension(container: String, number: Int, fileDesc: FileDescriptor): Boolean =
    fileDesc.getExtensions.iterator.asScala.exists(ext => container == ext.getContainingType.getFullName && number == ext.getNumber)

  private final def findFileDescForExtension(container: String, number: Int, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsExtension(container, number, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForExtension(container, number, fd)).find(_.isDefined).flatten

  private final def findExtensionNumbersForContainingType(container: String, fileDesc: FileDescriptor): List[Int] = 
    fileDesc.getDependencies.iterator.asScala.foldLeft(
      fileDesc.getExtensions.iterator.asScala.collect({ case ext if ext.getFullName == container => ext.getNumber }).toList
    )((list, fd) => findExtensionNumbersForContainingType(container, fd) ::: list)

  private def handle(fileDesc: FileDescriptor): Flow[ServerReflectionRequest, ServerReflectionResponse, NotUsed] =
    Flow[ServerReflectionRequest]/*DEBUG: .alsoTo(Sink.foreach(println(_)))*/.map(req => {
      import ServerReflectionRequest.{ MessageRequest => In}
      import ServerReflectionResponse.{ MessageResponse => Out}

      val response = req.messageRequest match {
        case In.Empty =>
          Out.Empty
        case In.FileByFilename(fileName) =>
          val list = findFileDescForName(fileName, fileDesc) match {
            case None => Nil // throw new GrpcServiceException(Status.NOT_FOUND.augmentDescription(s"File not found: $fileName"))
            case Some(file) => file.toProto.toByteString :: Nil
          }
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingSymbol(symbol) =>
          val list = findFileDescForSymbol(symbol, fileDesc) match {
            case None => Nil // throw new GrpcServiceException(Status.NOT_FOUND.augmentDescription(s"Symbol not found: $symbol"))
            case Some(file) => file.toProto.toByteString :: Nil
          }
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingExtension(ExtensionRequest(container, number)) =>
          val list = findFileDescForExtension(container, number, fileDesc) match {
            case None => Nil // throw new GrpcServiceException(Status.NOT_FOUND.augmentDescription(s"Extensions not found for: $container"))
            case Some(file) => file.toProto.toByteString :: Nil
          }
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.AllExtensionNumbersOfType(container) =>
          // TODO should we throw a NOT_FOUND if we don't know the container type at all?
          val list = findExtensionNumbersForContainingType(container, fileDesc)
          Out.AllExtensionNumbersResponse(ExtensionNumberResponse(container, list))
        case In.ListServices(_)              =>
          val list = fileDesc.getServices.iterator.asScala.map(s => ServiceResponse(s.getFullName)).toList
          Out.ListServicesResponse(ListServiceResponse(list))
      }
      // TODO Validate assumptions here
      ServerReflectionResponse(req.host, Some(req), response)
    })// DEBUG: .alsoTo(Sink.foreach(println(_)))
}
