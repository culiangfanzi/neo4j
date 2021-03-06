/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v2_2.planner.execution

import org.neo4j.cypher.internal.compiler.v2_2._
import org.neo4j.cypher.internal.compiler.v2_2.ast.convert.commands.ExpressionConverters._
import org.neo4j.cypher.internal.compiler.v2_2.ast.convert.commands.OtherConverters._
import org.neo4j.cypher.internal.compiler.v2_2.ast.convert.commands.PatternConverters._
import org.neo4j.cypher.internal.compiler.v2_2.ast.convert.commands.StatementConverters
import org.neo4j.cypher.internal.compiler.v2_2.ast.rewriters.projectNamedPaths
import org.neo4j.cypher.internal.compiler.v2_2.ast.{Expression, Identifier, NodeStartItem, RelTypeName}
import org.neo4j.cypher.internal.compiler.v2_2.commands.expressions.{AggregationExpression, Expression => CommandExpression}
import org.neo4j.cypher.internal.compiler.v2_2.commands.{Predicate => CommandPredicate, Equals, EntityProducerFactory, True}
import org.neo4j.cypher.internal.compiler.v2_2.executionplan.builders.prepare.KeyTokenResolver
import org.neo4j.cypher.internal.compiler.v2_2.executionplan.{PipeInfo, PlanFingerprint}
import org.neo4j.cypher.internal.compiler.v2_2.pipes._
import org.neo4j.cypher.internal.compiler.v2_2.planner.logical.Metrics
import org.neo4j.cypher.internal.compiler.v2_2.planner.logical.Metrics.QueryGraphCardinalityInput
import org.neo4j.cypher.internal.compiler.v2_2.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v2_2.planner.{CantHandleQueryException, SemanticTable}
import org.neo4j.cypher.internal.compiler.v2_2.spi.{InstrumentedGraphStatistics, PlanContext}
import org.neo4j.cypher.internal.compiler.v2_2.symbols.SymbolTable
import org.neo4j.cypher.internal.helpers.Eagerly
import org.neo4j.graphdb.{Direction, Relationship}
import org.neo4j.helpers.Clock
import org.neo4j.cypher.internal.compiler.v2_2.pipes.LazyTypes

case class PipeExecutionBuilderContext(cardinality: Metrics.CardinalityModel, semanticTable: SemanticTable)

class PipeExecutionPlanBuilder(clock: Clock, monitors: Monitors) {

  val entityProducerFactory = new EntityProducerFactory
  val resolver = new KeyTokenResolver

  def build(plan: LogicalPlan)(implicit context: PipeExecutionBuilderContext, planContext: PlanContext): PipeInfo = {
    val updating = false

    def buildPipe(plan: LogicalPlan, input: QueryGraphCardinalityInput): Pipe = {
      implicit val monitor = monitors.newMonitor[PipeMonitor]()
      implicit val c = context.cardinality

      val result: Pipe with RonjaPipe = plan match {
        case Projection(left, expressions) =>
          ProjectionNewPipe(buildPipe(left, input), Eagerly.immutableMapValues(expressions, buildExpression))()

        case ProjectEndpoints(left, rel, start, end, directed, length) =>
          ProjectEndpointsPipe(buildPipe(left, input), rel.name, start.name, end.name, directed, length.isSimple)()

        case sr @ SingleRow() =>
          SingleRowPipe()

        case sr @ Argument(ids) =>
          ArgumentPipe(new SymbolTable(sr.typeInfo))()

        case AllNodesScan(IdName(id), _) =>
          AllNodesScanPipe(id)()

        case NodeByLabelScan(IdName(id), label, _) =>
          NodeByLabelScanPipe(id, label)()

        case NodeByIdSeek(IdName(id), nodeIdExpr, _) =>
          NodeByIdSeekPipe(id, nodeIdExpr.asEntityByIdRhs)()

        case DirectedRelationshipByIdSeek(IdName(id), relIdExpr, IdName(fromNode), IdName(toNode), _) =>
          DirectedRelationshipByIdSeekPipe(id, relIdExpr.asEntityByIdRhs, toNode, fromNode)()

        case UndirectedRelationshipByIdSeek(IdName(id), relIdExpr, IdName(fromNode), IdName(toNode), _) =>
          UndirectedRelationshipByIdSeekPipe(id, relIdExpr.asEntityByIdRhs, toNode, fromNode)()

        case NodeIndexSeek(IdName(id), label, propertyKey, valueExpr, _) =>
          NodeIndexSeekPipe(id, label, propertyKey, valueExpr.map(buildExpression), unique = false)()

        case NodeIndexUniqueSeek(IdName(id), label, propertyKey, valueExpr, _) =>
          NodeIndexSeekPipe(id, label, propertyKey, valueExpr.map(buildExpression), unique = true)()

        case Selection(predicates, left) =>
          FilterPipe(buildPipe(left, input), predicates.map(buildPredicate).reduce(_ ++ _))()

        case CartesianProduct(left, right) =>
          CartesianProductPipe(buildPipe(left, input), buildPipe(right, input))()

        case Expand(left, IdName(fromName), dir, types: Seq[RelTypeName], IdName(toName), IdName(relName), ExpandAll) =>
          implicit val table: SemanticTable = context.semanticTable
          buildExpandPipe(types, buildPipe(left, input), fromName, relName, toName, dir)

        case OptionalExpand(left, IdName(fromName), dir, types, IdName(toName), IdName(relName), ExpandAll, predicates) =>
          val predicate = predicates.map(buildPredicate).reduceOption(_ ++ _).getOrElse(True())
          implicit val table: SemanticTable = context.semanticTable
          OptionalExpandPipe(buildPipe(left, input), fromName, relName, toName, dir, LazyTypes(types), predicate)()

        case Expand(left, IdName(fromName), dir, types: Seq[RelTypeName], IdName(toName), IdName(relName), ExpandInto) =>
          implicit val table: SemanticTable = context.semanticTable
          val tmpName = toName + "$$$"
          val expandPipe = buildExpandPipe(types, buildPipe(left, input), fromName, relName, tmpName, dir)
          val id1: CommandExpression = commands.expressions.Identifier(toName)
          val id2: CommandExpression = commands.expressions.Identifier(tmpName)
          val pred: CommandPredicate = commands.Equals(id1, id2)
          FilterPipe(expandPipe, pred)()

        case OptionalExpand(left, IdName(fromName), dir, types, IdName(toName), IdName(relName), ExpandInto, predicates) =>
          val tmpName = toName + "$$$"
          val id1: CommandExpression = commands.expressions.Identifier(toName)
          val id2: CommandExpression = commands.expressions.Identifier(tmpName)
          val pred: CommandPredicate = commands.Equals(id1, id2)
          val predicate = (predicates.map(buildPredicate) :+ pred).reduceOption(_ ++ _).getOrElse(True())
          implicit val table: SemanticTable = context.semanticTable
          OptionalExpandPipe(buildPipe(left, input), fromName, relName, tmpName, dir, LazyTypes(types), predicate)()

        case VarExpand(left, IdName(fromName), dir, projectedDir, types, IdName(toName), IdName(relName), VarPatternLength(min, max), expansionMode, predicates) =>
          val (keys, exprs) = predicates.unzip
          val commands = exprs.map(buildPredicate)
          val predicate = (context: ExecutionContext, state: QueryState, rel: Relationship) => {
            keys.zip(commands).forall { case (identifier: Identifier, expr: CommandPredicate) =>
              context(identifier.name) = rel
              val result = expr.isTrue(context)(state)
              context.remove(identifier.name)
              result
            }
          }

          implicit val table: SemanticTable = context.semanticTable

          VarLengthExpandPipe(buildPipe(left, input), fromName, relName, toName, dir, projectedDir, LazyTypes(types), min, max, predicate)()

        case NodeHashJoin(nodes, left, right) =>
          NodeHashJoinPipe(nodes.map(_.name), buildPipe(left, input), buildPipe(right, input))()

        case OuterHashJoin(nodes, left, right) =>
          NodeOuterHashJoinPipe(nodes.map(_.name), buildPipe(left, input), buildPipe(right, input), (right.availableSymbols -- left.availableSymbols).map(_.name))()

        case Optional(inner) =>
          OptionalPipe(inner.availableSymbols.map(_.name), buildPipe(inner, input))()

        case Apply(outer, inner) =>
          ApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)))()

        case SemiApply(outer, inner) =>
          SemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), negated = false)()

        case AntiSemiApply(outer, inner) =>
          SemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), negated = true)()

        case LetSemiApply(outer, inner, idName) =>
          LetSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), idName.name, negated = false)()

        case LetAntiSemiApply(outer, inner, idName) =>
          LetSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), idName.name, negated = true)()

        case apply@SelectOrSemiApply(outer, inner, predicate) =>
          SelectOrSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), buildPredicate(predicate), negated = false)()

        case apply@SelectOrAntiSemiApply(outer, inner, predicate) =>
          SelectOrSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), buildPredicate(predicate), negated = true)()

        case apply@LetSelectOrSemiApply(outer, inner, idName, predicate) =>
          LetSelectOrSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), idName.name, buildPredicate(predicate), negated = false)()

        case apply@LetSelectOrAntiSemiApply(outer, inner, idName, predicate) =>
          LetSelectOrSemiApplyPipe(buildPipe(outer, input), buildPipe(inner, input.recurse(outer)), idName.name, buildPredicate(predicate), negated = true)()

        case Sort(left, sortItems) =>
          SortPipe(buildPipe(left, input), sortItems)()

        case Skip(lhs, count) =>
          SkipPipe(buildPipe(lhs, input), buildExpression(count))()

        case Limit(lhs, count) =>
          LimitPipe(buildPipe(lhs, input), buildExpression(count))()

        case SortedLimit(lhs, exp, sortItems) =>
          TopPipe(buildPipe(lhs, input), sortItems.map(_.asCommandSortItem).toList, exp.asCommandExpression)()

        // TODO: Maybe we shouldn't encode distinct as an empty aggregation.
        case Aggregation(Projection(source, expressions), groupingExpressions, aggregatingExpressions)
          if aggregatingExpressions.isEmpty && expressions == groupingExpressions =>
          DistinctPipe(buildPipe(source, input), groupingExpressions.mapValues(_.asCommandExpression))()

        case Aggregation(source, groupingExpressions, aggregatingExpressions) if aggregatingExpressions.isEmpty =>
          DistinctPipe(buildPipe(source, input), groupingExpressions.mapValues(_.asCommandExpression))()

        case Aggregation(source, groupingExpressions, aggregatingExpressions) =>
          EagerAggregationPipe(
            buildPipe(source, input),
            Eagerly.immutableMapValues[String, ast.Expression, commands.expressions.Expression](groupingExpressions, x => x.asCommandExpression),
            Eagerly.immutableMapValues[String, ast.Expression, AggregationExpression](aggregatingExpressions, x => x.asCommandExpression.asInstanceOf[AggregationExpression])
          )()

        case FindShortestPaths(source, shortestPath) =>
          val legacyShortestPaths = shortestPath.expr.asLegacyPatterns(shortestPath.name.map(_.name))
          val legacyShortestPath = legacyShortestPaths.head
          new ShortestPathPipe(buildPipe(source, input), legacyShortestPath)()

        case Union(lhs, rhs) =>
          NewUnionPipe(buildPipe(lhs, input), buildPipe(rhs, input))()

        case UnwindCollection(lhs, identifier, collection) =>
          UnwindPipe(buildPipe(lhs, input), collection.asCommandExpression, identifier.name)()

        case LegacyIndexSeek(id, hint: NodeStartItem, _) =>
          val source = new SingleRowPipe()
          val ep = entityProducerFactory.nodeStartItems((planContext, StatementConverters.StartItemConverter(hint).asCommandStartItem))
          NodeStartPipe(source, id.name, ep)()

        case x =>
          throw new CantHandleQueryException(x.toString)
      }

      val cardinality = context.cardinality(plan, input)
      result.withEstimatedCardinality(cardinality.amount.toLong)
    }

    object buildPipeExpressions extends Rewriter {
      val instance = Rewriter.lift {
        case ast.NestedPlanExpression(patternPlan, pattern) =>
          val pos = pattern.position
          val pipe = buildPipe(patternPlan, QueryGraphCardinalityInput.empty)
          val step = projectNamedPaths.patternPartPathExpression(ast.EveryPath(pattern.pattern.element))
          val result = ast.NestedPipeExpression(pipe, ast.PathExpression(step)(pos))(pos)
          result
      }

      def apply(that: AnyRef): AnyRef = bottomUp(instance).apply(that)
    }

    def buildExpandPipe(types: Seq[RelTypeName], left: Pipe, fromName: String, relName: String, toName: String, dir: Direction)
                       (implicit table: SemanticTable, monitor: PipeMonitor) =
        ExpandPipe(left, fromName, relName, toName, dir, LazyTypes(types))()

    def buildExpression(expr: ast.Expression): CommandExpression = {
      val rewrittenExpr = expr.endoRewrite(buildPipeExpressions)

      rewrittenExpr.asCommandExpression.rewrite(resolver.resolveExpressions(_, planContext))
    }

    def buildPredicate(expr: ast.Expression): CommandPredicate = {
      val rewrittenExpr: Expression = expr.endoRewrite(buildPipeExpressions)

      rewrittenExpr.asCommandPredicate.rewrite(resolver.resolveExpressions(_, planContext)).asInstanceOf[CommandPredicate]
    }

    val topLevelPipe = buildPipe(plan, QueryGraphCardinalityInput.empty)

    val fingerprint = planContext.statistics match {
      case igs: InstrumentedGraphStatistics =>
        Some(PlanFingerprint(clock.currentTimeMillis(), planContext.txIdProvider(), igs.snapshot.freeze))
      case _ =>
        None
    }

    PipeInfo(topLevelPipe, updating, None, fingerprint, Ronja)
  }
}
