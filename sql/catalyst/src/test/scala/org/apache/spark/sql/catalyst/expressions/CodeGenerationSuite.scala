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

package org.apache.spark.sql.catalyst.expressions

import java.sql.Timestamp

import org.apache.spark.SparkFunSuite
import org.apache.spark.metrics.source.CodegenMetrics
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.expressions.objects.{AssertNotNull, CreateExternalRow, GetExternalRowField, ValidateExternalType}
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, DateTimeUtils}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.ThreadUtils

/**
 * Additional tests for code generation.
 */
class CodeGenerationSuite extends SparkFunSuite with ExpressionEvalHelper {

  test("multithreaded eval") {
    import scala.concurrent._
    import ExecutionContext.Implicits.global
    import scala.concurrent.duration._

    val futures = (1 to 20).map { _ =>
      Future {
        GeneratePredicate.generate(EqualTo(Literal(1), Literal(1)))
        GenerateMutableProjection.generate(EqualTo(Literal(1), Literal(1)) :: Nil)
        GenerateOrdering.generate(Add(Literal(1), Literal(1)).asc :: Nil)
      }
    }

    futures.foreach(ThreadUtils.awaitResult(_, 10.seconds))
  }

  test("metrics are recorded on compile") {
    val startCount1 = CodegenMetrics.METRIC_COMPILATION_TIME.getCount()
    val startCount2 = CodegenMetrics.METRIC_SOURCE_CODE_SIZE.getCount()
    val startCount3 = CodegenMetrics.METRIC_GENERATED_CLASS_BYTECODE_SIZE.getCount()
    val startCount4 = CodegenMetrics.METRIC_GENERATED_METHOD_BYTECODE_SIZE.getCount()
    GenerateOrdering.generate(Add(Literal(123), Literal(1)).asc :: Nil)
    assert(CodegenMetrics.METRIC_COMPILATION_TIME.getCount() == startCount1 + 1)
    assert(CodegenMetrics.METRIC_SOURCE_CODE_SIZE.getCount() == startCount2 + 1)
    assert(CodegenMetrics.METRIC_GENERATED_CLASS_BYTECODE_SIZE.getCount() > startCount3)
    assert(CodegenMetrics.METRIC_GENERATED_METHOD_BYTECODE_SIZE.getCount() > startCount4)
  }

  test("SPARK-8443: split wide projections into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = List.fill(length)(EqualTo(Literal(1), Literal(1)))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    val expected = Seq.fill(length)(true)

    if (actual != expected) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-13242: case-when expression with large number of branches (or cases)") {
    val cases = 50
    val clauses = 20

    // Generate an individual case
    def generateCase(n: Int): (Expression, Expression) = {
      val condition = (1 to clauses)
        .map(c => EqualTo(BoundReference(0, StringType, false), Literal(s"$c:$n")))
        .reduceLeft[Expression]((l, r) => Or(l, r))
      (condition, Literal(n))
    }

    val expression = CaseWhen((1 to cases).map(generateCase(_)))

    val plan = GenerateMutableProjection.generate(Seq(expression))
    val input = new GenericInternalRow(Array[Any](UTF8String.fromString(s"${clauses}:${cases}")))
    val actual = plan(input).toSeq(Seq(expression.dataType))

    assert(actual(0) == cases)
  }

  test("SPARK-18091: split large if expressions into blocks due to JVM code size limit") {
    var strExpr: Expression = Literal("abc")
    for (_ <- 1 to 150) {
      strExpr = Decode(Encode(strExpr, "utf-8"), "utf-8")
    }

    val expressions = Seq(If(EqualTo(strExpr, strExpr), strExpr, strExpr))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(null).toSeq(expressions.map(_.dataType))
    assert(actual.length == 1)
    val expected = UTF8String.fromString("abc")

    if (!checkResult(actual.head, expected, expressions.head.dataType)) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-14793: split wide array creation into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = Seq(CreateArray(List.fill(length)(EqualTo(Literal(1), Literal(1)))))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    assert(actual.length == 1)
    val expected = UnsafeArrayData.fromPrimitiveArray(Array.fill(length)(true))

    if (!checkResult(actual.head, expected, expressions.head.dataType)) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-14793: split wide map creation into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = Seq(CreateMap(
      List.fill(length)(EqualTo(Literal(1), Literal(1))).zipWithIndex.flatMap {
        case (expr, i) => Seq(Literal(i), expr)
      }))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    assert(actual.length == 1)
    val expected = ArrayBasedMapData((0 until length).toArray, Array.fill(length)(true))

    if (!checkResult(actual.head, expected, expressions.head.dataType)) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-14793: split wide struct creation into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = Seq(CreateStruct(List.fill(length)(EqualTo(Literal(1), Literal(1)))))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    val expected = Seq(InternalRow(Seq.fill(length)(true): _*))

    if (!checkResult(actual, expected, expressions.head.dataType)) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-14793: split wide named struct creation into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = Seq(CreateNamedStruct(
      List.fill(length)(EqualTo(Literal(1), Literal(1))).flatMap {
        expr => Seq(Literal(expr.toString), expr)
      }))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    assert(actual.length == 1)
    val expected = InternalRow(Seq.fill(length)(true): _*)

    if (!checkResult(actual.head, expected, expressions.head.dataType)) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-14224: split wide external row creation into blocks due to JVM code size limit") {
    val length = 5000
    val schema = StructType(Seq.fill(length)(StructField("int", IntegerType)))
    val expressions = Seq(CreateExternalRow(Seq.fill(length)(Literal(1)), schema))
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    val expected = Seq(Row.fromSeq(Seq.fill(length)(1)))

    if (actual != expected) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("SPARK-17702: split wide constructor into blocks due to JVM code size limit") {
    val length = 5000
    val expressions = Seq.fill(length) {
      ToUTCTimestamp(
        Literal.create(Timestamp.valueOf("2015-07-24 00:00:00"), TimestampType),
        Literal.create("PST", StringType))
    }
    val plan = GenerateMutableProjection.generate(expressions)
    val actual = plan(new GenericInternalRow(length)).toSeq(expressions.map(_.dataType))
    val expected = Seq.fill(length)(
      DateTimeUtils.fromJavaTimestamp(Timestamp.valueOf("2015-07-24 07:00:00")))

    if (actual != expected) {
      fail(s"Incorrect Evaluation: expressions: $expressions, actual: $actual, expected: $expected")
    }
  }

  test("test generated safe and unsafe projection") {
    val schema = new StructType(Array(
      StructField("a", StringType, true),
      StructField("b", IntegerType, true),
      StructField("c", new StructType(Array(
        StructField("aa", StringType, true),
        StructField("bb", IntegerType, true)
      )), true),
      StructField("d", new StructType(Array(
        StructField("a", new StructType(Array(
          StructField("b", StringType, true),
          StructField("", IntegerType, true)
        )), true)
      )), true)
    ))
    val row = Row("a", 1, Row("b", 2), Row(Row("c", 3)))
    val lit = Literal.create(row, schema)
    val internalRow = lit.value.asInstanceOf[InternalRow]

    val unsafeProj = UnsafeProjection.create(schema)
    val unsafeRow: UnsafeRow = unsafeProj(internalRow)
    assert(unsafeRow.getUTF8String(0) === UTF8String.fromString("a"))
    assert(unsafeRow.getInt(1) === 1)
    assert(unsafeRow.getStruct(2, 2).getUTF8String(0) === UTF8String.fromString("b"))
    assert(unsafeRow.getStruct(2, 2).getInt(1) === 2)
    assert(unsafeRow.getStruct(3, 1).getStruct(0, 2).getUTF8String(0) ===
      UTF8String.fromString("c"))
    assert(unsafeRow.getStruct(3, 1).getStruct(0, 2).getInt(1) === 3)

    val fromUnsafe = FromUnsafeProjection(schema)
    val internalRow2 = fromUnsafe(unsafeRow)
    assert(internalRow === internalRow2)

    // update unsafeRow should not affect internalRow2
    unsafeRow.setInt(1, 10)
    unsafeRow.getStruct(2, 2).setInt(1, 10)
    unsafeRow.getStruct(3, 1).getStruct(0, 2).setInt(1, 4)
    assert(internalRow === internalRow2)
  }

  test("*/ in the data") {
    // When */ appears in a comment block (i.e. in /**/), code gen will break.
    // So, in Expression and CodegenFallback, we escape */ to \*\/.
    checkEvaluation(
      EqualTo(BoundReference(0, StringType, false), Literal.create("*/", StringType)),
      true,
      InternalRow(UTF8String.fromString("*/")))
  }

  test("\\u in the data") {
    // When \ u appears in a comment block (i.e. in /**/), code gen will break.
    // So, in Expression and CodegenFallback, we escape \ u to \\u.
    checkEvaluation(
      EqualTo(BoundReference(0, StringType, false), Literal.create("\\u", StringType)),
      true,
      InternalRow(UTF8String.fromString("\\u")))
  }

  test("check compilation error doesn't occur caused by specific literal") {
    // The end of comment (*/) should be escaped.
    GenerateUnsafeProjection.generate(
      Literal.create("*/Compilation error occurs/*", StringType) :: Nil)

    // `\u002A` is `*` and `\u002F` is `/`
    // so if the end of comment consists of those characters in queries, we need to escape them.
    GenerateUnsafeProjection.generate(
      Literal.create("\\u002A/Compilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\\\u002A/Compilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\u002a/Compilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\\\u002a/Compilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("*\\u002FCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("*\\\\u002FCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("*\\002fCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("*\\\\002fCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\002A\\002FCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\\\002A\\002FCompilation error occurs/*", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\002A\\\\002FCompilation error occurs/*", StringType) :: Nil)

    // \ u002X is an invalid unicode literal so it should be escaped.
    GenerateUnsafeProjection.generate(
      Literal.create("\\u002X/Compilation error occurs", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\\\u002X/Compilation error occurs", StringType) :: Nil)

    // \ u001 is an invalid unicode literal so it should be escaped.
    GenerateUnsafeProjection.generate(
      Literal.create("\\u001/Compilation error occurs", StringType) :: Nil)
    GenerateUnsafeProjection.generate(
      Literal.create("\\\\u001/Compilation error occurs", StringType) :: Nil)

  }

  test("SPARK-17160: field names are properly escaped by GetExternalRowField") {
    val inputObject = BoundReference(0, ObjectType(classOf[Row]), nullable = true)
    GenerateUnsafeProjection.generate(
      ValidateExternalType(
        GetExternalRowField(inputObject, index = 0, fieldName = "\"quote"), IntegerType) :: Nil)
  }

  test("SPARK-17160: field names are properly escaped by AssertTrue") {
    GenerateUnsafeProjection.generate(AssertTrue(Cast(Literal("\""), BooleanType)) :: Nil)
  }

  test("should not apply common subexpression elimination on conditional expressions") {
    val row = InternalRow(null)
    val bound = BoundReference(0, IntegerType, true)
    val assertNotNull = AssertNotNull(bound, Nil)
    val expr = If(IsNull(bound), Literal(1), Add(assertNotNull, assertNotNull))
    val projection = GenerateUnsafeProjection.generate(
      Seq(expr), subexpressionEliminationEnabled = true)
    // should not throw exception
    projection(row)
  }
}
