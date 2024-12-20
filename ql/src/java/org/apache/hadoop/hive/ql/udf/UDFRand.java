/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.udf;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDF;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedExpressions;
import org.apache.hadoop.hive.ql.exec.vector.expressions.FuncRand;
import org.apache.hadoop.hive.ql.exec.vector.expressions.FuncRandNoSeed;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;

import java.util.Random;

/**
 * UDFRand.
 *
 */
@Description(name = "rand",
    value = "_FUNC_([seed]) - Returns a pseudorandom number between 0 and 1")
@UDFType(deterministic = false)
@VectorizedExpressions({FuncRandNoSeed.class, FuncRand.class})
public class UDFRand extends UDF {
  private Random random;

  private final DoubleWritable result = new DoubleWritable();

  public UDFRand() {
  }

  public DoubleWritable evaluate() {
    if (random == null) {
      random = new Random();
    }
    result.set(random.nextDouble());
    return result;
  }


  /**
   * 传入固定种子数，生成固定的随机double值
   * @param seed
   * @return
   */
  public DoubleWritable evaluate(LongWritable seed) {
    if (random == null) {
      long seedValue = 0;
      if (seed != null) {
        seedValue = seed.get();
      }
      random = new Random(seedValue);
    }
    result.set(random.nextDouble());
    return result;
  }

}
