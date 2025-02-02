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

package org.grapheco.lynx

import com.typesafe.scalalogging.LazyLogging
import org.grapheco.lynx.execution.utils.OperatorUtils
import org.grapheco.lynx.graph.GraphModel
import org.grapheco.lynx.logical.LogicalNode
import org.grapheco.lynx.logical.plan.{DefaultLogicalPlanner, LogicalPlanner, LogicalPlannerContext}
import org.grapheco.lynx.physical.PhysicalNode
import org.grapheco.lynx.physical.plan.{DefaultPhysicalPlanner, PhysicalPlanner, PhysicalPlannerContext}
import org.grapheco.lynx.procedure.functions.{AggregatingFunctions, ListFunctions, LogarithmicFunctions, NumericFunctions, PredicateFunctions, ScalarFunctions, StringFunctions, TimeFunctions, TrigonometricFunctions}
import org.grapheco.lynx.procedure.{DefaultProcedureRegistry, ProcedureRegistry}
import org.grapheco.lynx.util.FormatUtils
import org.grapheco.lynx.types.{DefaultTypeSystem, LynxResult, LynxValue, TypeSystem}
import org.opencypher.v9_0.ast.Statement
import org.opencypher.v9_0.ast.semantics.SemanticState
import org.grapheco.metrics.DomainObject

import java.time.LocalDateTime

case class CypherRunnerContext(
    typeSystem: TypeSystem,
    procedureRegistry: ProcedureRegistry,
    dataFrameOperator: DataFrameOperator,
    expressionEvaluator: ExpressionEvaluator,
    graphModel: GraphModel)

class CypherRunner(graphModel: GraphModel) extends LazyLogging {
  protected lazy val types: TypeSystem = new DefaultTypeSystem()
  protected lazy val procedures: DefaultProcedureRegistry = new DefaultProcedureRegistry(
    types,
    classOf[AggregatingFunctions],
    classOf[ListFunctions],
    classOf[LogarithmicFunctions],
    classOf[NumericFunctions],
    classOf[PredicateFunctions],
    classOf[ScalarFunctions],
    classOf[StringFunctions],
    classOf[TimeFunctions],
    classOf[TrigonometricFunctions]
  )
  protected lazy val expressionEvaluator: ExpressionEvaluator =
    new DefaultExpressionEvaluator(graphModel, types, procedures)
  protected lazy val dataFrameOperator: DataFrameOperator = new DefaultDataFrameOperator(
    expressionEvaluator
  )
  private implicit lazy val runnerContext =
    CypherRunnerContext(types, procedures, dataFrameOperator, expressionEvaluator, graphModel)
  protected lazy val logicalPlanner: LogicalPlanner = new DefaultLogicalPlanner(runnerContext)
  protected lazy val physicalPlanner: PhysicalPlanner = new DefaultPhysicalPlanner(runnerContext)
  protected lazy val physicalPlanOptimizer: PhysicalPlanOptimizer =
    new DefaultPhysicalPlanOptimizer(runnerContext)
  protected lazy val queryParser: QueryParser = new CachedQueryParser(
    new DefaultQueryParser(runnerContext)
  )

  def compile(query: String): (Statement, Map[String, Any], SemanticState) =
    queryParser.parse(query)

  def run(query: String, param: Map[String, Any]): LynxResult = {
    DomainObject.pushLabel(query)

    DomainObject.recordLatency(null)

    DomainObject.pushLabel("ast-plan")
    DomainObject.recordLatency(null)
    val (statement, param2, state) = queryParser.parse(query)
    logger.debug(s"AST tree: ${statement}")
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    val logicalPlannerContext = LogicalPlannerContext(param ++ param2, runnerContext)
    DomainObject.pushLabel("logical-plan")
    DomainObject.recordLatency(null)
    val logicalPlan = logicalPlanner.plan(statement, logicalPlannerContext)
    logger.debug(s"logical plan: \r\n${logicalPlan.pretty}")
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    val physicalPlannerContext = PhysicalPlannerContext(param ++ param2, runnerContext)
    DomainObject.pushLabel("physical-plan")
    DomainObject.recordLatency(null)
    val physicalPlan = physicalPlanner.plan(logicalPlan)(physicalPlannerContext)
    logger.debug(s"physical plan: \r\n${physicalPlan.pretty}")
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    DomainObject.pushLabel("optimizer")
    DomainObject.recordLatency(null)
    val optimizedPhysicalPlan = physicalPlanOptimizer.optimize(physicalPlan, physicalPlannerContext)
    logger.debug(s"optimized physical plan: \r\n${optimizedPhysicalPlan.pretty}")
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    val ctx = ExecutionContext(physicalPlannerContext, statement, param ++ param2)

    DomainObject.pushLabel("execution plan")
    DomainObject.recordLatency(null)
    val executionPlanCreator = new ExecutionPlanCreator()
    val executionPlan =
      executionPlanCreator.translate(optimizedPhysicalPlan, physicalPlannerContext, ctx)
    logger.debug(s"execution plan: \r\n${executionPlan.pretty}")
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    DomainObject.pushLabel("execute")
    DomainObject.recordLatency(null)
    val result =
      OperatorUtils.getOperatorAllOutputs(executionPlan).flatMap(inputBatch => inputBatch.batchData)
    graphModel.write.commit
    DomainObject.recordLatency(null)
    DomainObject.popLabel()

    DomainObject.recordLatency(null)

    DomainObject.printRecords(Set(query))

    new LynxResult() with PlanAware {
      val schema = executionPlan.outputSchema()
      val columnNames = schema.map(_._1)

      override def show(limit: Int): Unit =
        FormatUtils.printTable(columnNames, result.take(limit))

      override def columns(): Seq[String] = columnNames

      override def records(): Iterator[Map[String, LynxValue]] =
        result.map(columnNames.zip(_).toMap).toIterator

      override def getASTStatement(): (Statement, Map[String, Any]) = (statement, param2)

      override def getLogicalPlan(): LogicalNode = logicalPlan

      override def getPhysicalPlan(): PhysicalNode = physicalPlan

      override def getOptimizerPlan(): PhysicalNode = optimizedPhysicalPlan

      override def cache(): LynxResult = {
        val source = this
        val cached = result.clone()

        new LynxResult {
          override def show(limit: Int): Unit =
            FormatUtils.printTable(columnNames, cached.take(limit))

          override def cache(): LynxResult = this

          override def columns(): Seq[String] = columnNames

          override def records(): Iterator[Map[String, LynxValue]] =
            cached.map(columnNames.zip(_).toMap).iterator

        }
      }
    }
  }
}
