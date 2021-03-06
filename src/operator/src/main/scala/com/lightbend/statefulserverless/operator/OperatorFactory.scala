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

package com.lightbend.statefulserverless.operator

import java.security.MessageDigest
import java.util.Base64

import akka.Done
import skuber.CustomResource
import skuber.api.client.KubernetesClient

import scala.concurrent.Future

trait OperatorFactory[Status, Resource <: CustomResource[_, Status]] {

  def apply(client: KubernetesClient): Operator

  /**
    * An operator.
    */
  trait Operator {

    /**
      * Return true if the status of this resource doesn't match the spec.
      *
      * Typically this is implemented by putting a hash of the spec in the status.
      */
    def hasAnythingChanged(resource: Resource): Boolean

    /**
      * Handle a resource being changed.
      *
      * @param resource The changed resource.
      * @return Optionally, the status to update, if the status should be updated.
      */
    def handleChanged(resource: Resource): Future[Option[Status]]

    /**
      * Handle a resource being deleted.
      *
      * @param resource The deleted resource.
      * @return A future that is redeemed when the operation is done.
      */
    def handleDeleted(resource: Resource): Future[Done]

    /**
      * Convert the given error to a status.
      *
      * @param error    The error to convert.
      * @param existing The existing resource, if it could be successfully parsed.
      * @return The status to set.
      */
    def statusFromError(error: Throwable, existing: Option[Resource] = None): Status

    /**
      * Convenience that can be used to calculate a hash of the passed in object.
      *
      * It converts the object to a String, then MD5s and base64s that.
      */
    def hashOf(obj: Any) = {
      val md = MessageDigest.getInstance("MD5")
      Base64.getEncoder.encodeToString(md.digest(obj.toString.getBytes("utf-8")))
    }
  }

}
