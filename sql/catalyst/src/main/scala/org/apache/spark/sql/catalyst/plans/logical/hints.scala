/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.internal.SQLConf

/**
 * A general hint for the child that is not yet resolved. This node is generated by the parser and
 * should be removed This node will be eliminated post analysis.
 * A pair of (name, parameters).
 */
case class UnresolvedHint(name: String, parameters: Seq[String], child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = false
  override def output: Seq[Attribute] = child.output
}

/**
 * A resolved hint node. The analyzer should convert all [[UnresolvedHint]] into [[ResolvedHint]].
 */
case class ResolvedHint(
    child: LogicalPlan,
    isBroadcastable: Option[Boolean] = None)
  extends UnaryNode {

  override def output: Seq[Attribute] = child.output

  override def computeStats(conf: SQLConf): Statistics = {
    val stats = child.stats(conf)
    isBroadcastable.map(x => stats.copy(isBroadcastable = x)).getOrElse(stats)
  }
}
