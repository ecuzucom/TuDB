// Copyright 2022 The TuDB Authors. All rights reserved.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.grapheco.lynx.physical.plan

import org.grapheco.lynx.{CypherRunnerContext, LynxType}

import scala.collection.mutable

/**
  *@description:
  */
object PhysicalPlannerContext {
  def apply(
      queryParameters: Map[String, Any],
      runnerContext: CypherRunnerContext
    ): PhysicalPlannerContext =
    new PhysicalPlannerContext(
      queryParameters.mapValues(runnerContext.typeSystem.wrap).mapValues(_.lynxType).toSeq,
      runnerContext
    )
}

case class PhysicalPlannerContext(
    parameterTypes: Seq[(String, LynxType)],
    runnerContext: CypherRunnerContext,
    var pptContext: mutable.Map[String, Any] = mutable.Map.empty) {}
