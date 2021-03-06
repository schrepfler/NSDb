/*
 * Copyright 2018-2020 Radicalbit S.r.l.
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

package io.radicalbit.nsdb.sql.parser

import io.radicalbit.nsdb.common.statement._
import org.scalatest.{Matchers, WordSpec}

import scala.util.Success

class AggregationSQLStatementSpec extends WordSpec with Matchers {

  private val parser = new SQLStatementParser

  "A SQL parser instance" when {

    "receive a select with a group by and one aggregation" should {
      "parse it successfully" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT sum(value) FROM people group by name") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              fields = ListFields(List(Field("value", Some(SumAggregation)))),
              groupBy = Some(SimpleGroupByAggregation("name"))
            )
          ))
      }
      "parse it successfully if sum(*) is provided" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT sum(*) FROM people group by name") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              fields = ListFields(List(Field("*", Some(SumAggregation)))),
              groupBy = Some(SimpleGroupByAggregation("name"))
            )
          ))
      }
      "parse it successfully if count(*) is provided" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT count(*) FROM people group by name") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              fields = ListFields(List(Field("*", Some(CountAggregation)))),
              groupBy = Some(SimpleGroupByAggregation("name"))
            )
          ))
      }
    }

    "receive a select containing a range selection and a group by" should {
      "parse it successfully" in {
        parser.parse(db = "db",
                     namespace = "registry",
                     input = "SELECT count(value) FROM people WHERE timestamp IN (2,4) group by name") should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(CountAggregation)))),
            condition = Some(Condition(RangeExpression(dimension = "timestamp",
                                                       value1 = AbsoluteComparisonValue(2L),
                                                       value2 = AbsoluteComparisonValue(4L)))),
            groupBy = Some(SimpleGroupByAggregation("name"))
          )))
      }
    }

    "receive a select containing a GTE selection and a group by" should {
      "parse it successfully" in {
        parser.parse(db = "db",
                     namespace = "registry",
                     input = "SELECT min(value) FROM people WHERE timestamp >= 10 group by name") should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(MinAggregation)))),
            condition = Some(Condition(ComparisonExpression(dimension = "timestamp",
                                                            comparison = GreaterOrEqualToOperator,
                                                            value = AbsoluteComparisonValue(10)))),
            groupBy = Some(SimpleGroupByAggregation("name"))
          )))
      }
    }

    "receive a select containing a GT AND a LTE selection and a group by" should {
      "parse it successfully" in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input = "SELECT max(value) FROM people WHERE timestamp > 2 AND timestamp <= 4 group by name") should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(MaxAggregation)))),
            condition = Some(Condition(TupledLogicalExpression(
              expression1 = ComparisonExpression(dimension = "timestamp",
                                                 comparison = GreaterThanOperator,
                                                 value = AbsoluteComparisonValue(2L)),
              operator = AndOperator,
              expression2 = ComparisonExpression(dimension = "timestamp",
                                                 comparison = LessOrEqualToOperator,
                                                 value = AbsoluteComparisonValue(4L))
            ))),
            groupBy = Some(SimpleGroupByAggregation("name"))
          )))
      }
    }

    "receive a select containing a ordering statement" should {
      "parse it successfully" in {
        parser.parse(db = "db",
                     namespace = "registry",
                     input = "SELECT count(value) FROM people group by name ORDER BY name") should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(CountAggregation)))),
            order = Some(AscOrderOperator("name")),
            groupBy = Some(SimpleGroupByAggregation("name"))
          )))
      }
    }

    "receive a select containing a ordering statement and a limit clause" should {
      "parse it successfully" in {
        parser.parse(db = "db",
                     namespace = "registry",
                     input = "SELECT count(value) FROM people group by name ORDER BY name limit 1") should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(CountAggregation)))),
            order = Some(AscOrderOperator("name")),
            groupBy = Some(SimpleGroupByAggregation("name")),
            limit = Some(LimitOperator(1))
          )))
      }
    }

    "receive a select containing uuids" should {
      "parse it successfully" in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input =
            "select count(value) from people where name = b483a480-832b-473e-a999-5d1a5950858d and surname = b483a480-832b group by surname"
        ) should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(CountAggregation)))),
            condition = Some(Condition(TupledLogicalExpression(
              expression1 = EqualityExpression(dimension = "name",
                                               value = AbsoluteComparisonValue("b483a480-832b-473e-a999-5d1a5950858d")),
              expression2 = EqualityExpression(dimension = "surname", value = AbsoluteComparisonValue("b483a480-832b")),
              operator = AndOperator
            ))),
            groupBy = Some(SimpleGroupByAggregation("surname"))
          )))

        parser.parse(
          db = "db",
          namespace = "registry",
          input =
            "select count(value) from people where na-me = b483a480-832b-473e-a999-5d1a5950858d and surname = b483a480-832b group by surname"
        ) shouldBe 'failure
      }
    }

    "receive a select containing uuids and more than 2 where" should {
      "parse it successfully" in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input =
            "select count(value) from people where prediction = 1.0 and adoptedModel = b483a480-832b-473e-a999-5d1a5950858d and id = c1234-56789 group by id"
        ) should be(
          Success(SelectSQLStatement(
            db = "db",
            namespace = "registry",
            metric = "people",
            distinct = false,
            fields = ListFields(List(Field("value", Some(CountAggregation)))),
            condition = Some(Condition(
              TupledLogicalExpression(
                EqualityExpression(dimension = "prediction", value = AbsoluteComparisonValue(1.0)),
                AndOperator,
                TupledLogicalExpression(
                  EqualityExpression(dimension = "adoptedModel",
                                     value = AbsoluteComparisonValue("b483a480-832b-473e-a999-5d1a5950858d")),
                  AndOperator,
                  EqualityExpression(dimension = "id", AbsoluteComparisonValue("c1234-56789"))
                )
              )
            )),
            groupBy = Some(SimpleGroupByAggregation("id"))
          )))
      }
    }

    "receive wrong fields" should {
      "fail" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT count(name,surname) FROM people") shouldBe 'failure
      }
    }

    "receive a select with a temporal group by with count aggregation in seconds" should {
      "parse it successfully" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT count(value) FROM people group by interval 3 s") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              fields = ListFields(List(Field("value", Some(CountAggregation)))),
              groupBy = Some(TemporalGroupByAggregation(3000, 3, "s"))
            )
          ))
      }
    }

    "receive a select with a temporal group by without measure in minutes" should {
      "parse it successfully" in {
        parser.parse(db = "db", namespace = "registry", input = "SELECT count(value) FROM people group by interval m") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              fields = ListFields(List(Field("value", Some(CountAggregation)))),
              groupBy = Some(TemporalGroupByAggregation(60000, 1, "m"))
            )
          ))
      }
    }

    "receive a select with a temporal group by with count aggregation with a limit" in {
      parser.parse(db = "db",
                   namespace = "registry",
                   input = "select count(value) from people group by interval 1d limit 1") should be(
        Success(SelectSQLStatement(
          db = "db",
          namespace = "registry",
          metric = "people",
          distinct = false,
          fields = ListFields(List(Field("value", Some(CountAggregation)))),
          groupBy = Some(TemporalGroupByAggregation(86400000, 1, "d")),
          limit = Some(LimitOperator(1))
        )))
    }

    "receive a select with a temporal group by, filtered by time with measure" should {
      "parse it successfully if the interval contains a space" in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input = "SELECT count(*) FROM people WHERE timestamp > 1 and timestamp < 100 group by interval 2 d") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              condition = Some(Condition(TupledLogicalExpression(
                expression1 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = GreaterThanOperator,
                                                         value = AbsoluteComparisonValue(1)),
                expression2 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = LessThanOperator,
                                                         value = AbsoluteComparisonValue(100)),
                operator = AndOperator
              ))),
              fields = ListFields(List(Field("*", Some(CountAggregation)))),
              groupBy = Some(TemporalGroupByAggregation(2 * 24 * 3600 * 1000, 2, "d"))
            )
          ))
      }

      "parse it successfully if the interval does not contain any space" in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input = "SELECT count(*) FROM people WHERE timestamp > 1 and timestamp < 100 group by interval 2d") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              condition = Some(Condition(TupledLogicalExpression(
                expression1 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = GreaterThanOperator,
                                                         value = AbsoluteComparisonValue(1)),
                expression2 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = LessThanOperator,
                                                         value = AbsoluteComparisonValue(100)),
                operator = AndOperator
              ))),
              fields = ListFields(List(Field("*", Some(CountAggregation)))),
              groupBy = Some(TemporalGroupByAggregation(2 * 24 * 3600 * 1000, 2, "d"))
            )
          ))
      }

      "parse it successfully if an aggregation different from count is provided " in {
        parser.parse(
          db = "db",
          namespace = "registry",
          input = "SELECT sum(*) FROM people WHERE timestamp > 1 and timestamp < 100 group by interval 2d") should be(
          Success(
            SelectSQLStatement(
              db = "db",
              namespace = "registry",
              metric = "people",
              distinct = false,
              condition = Some(Condition(TupledLogicalExpression(
                expression1 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = GreaterThanOperator,
                                                         value = AbsoluteComparisonValue(1)),
                expression2 = ComparisonExpression[Long](dimension = "timestamp",
                                                         comparison = LessThanOperator,
                                                         value = AbsoluteComparisonValue(100)),
                operator = AndOperator
              ))),
              fields = ListFields(List(Field("*", Some(SumAggregation)))),
              groupBy = Some(TemporalGroupByAggregation(2 * 24 * 3600 * 1000, 2, "d"))
            )
          ))
      }
    }
  }
}
