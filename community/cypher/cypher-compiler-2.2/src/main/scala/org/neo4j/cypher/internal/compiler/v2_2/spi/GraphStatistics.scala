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
package org.neo4j.cypher.internal.compiler.v2_2.spi

import org.neo4j.cypher.internal.compiler.v2_2.planner.logical.{Cardinality, Selectivity}
import org.neo4j.cypher.internal.compiler.v2_2.{LabelId, PropertyKeyId, RelTypeId}

trait GraphStatistics {
  def nodesWithLabelCardinality(labelId: Option[LabelId]): Cardinality

  def cardinalityByLabelsAndRelationshipType(fromLabel: Option[LabelId], relTypeId: Option[RelTypeId], toLabel: Option[LabelId]): Cardinality

  /*
      Probability of any node with the given label, to have a property with a given value

      indexSelectivity(:X, prop) = s => |MATCH (a:X)| * s = |MATCH (a:X) WHERE x.prop = *|
   */
  def indexSelectivity(label: LabelId, property: PropertyKeyId): Option[Selectivity]
}

object GraphStatistics {
  val DEFAULT_RANGE_SELECTIVITY          = Selectivity(0.3)
  val DEFAULT_PREDICATE_SELECTIVITY      = Selectivity(0.75)
  val DEFAULT_EQUALITY_SELECTIVITY       = Selectivity(0.1)
  val DEFAULT_NUMBER_OF_ID_LOOKUPS       = Cardinality(25)
  val DEFAULT_REL_UNIQUENESS_SELECTIVITY = Selectivity(1.0 - 1 / 100 /*rel-cardinality*/)
}
