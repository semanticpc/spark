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

package org.apache.spark.examples.streaming;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.regex.Pattern;

import scala.Tuple2;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.api.java.JavaStreamingContextFactory;

/**
 * Counts words in text encoded with UTF8 received from the network every second.
 *
 * Usage: JavaRecoverableNetworkWordCount <hostname> <port> <checkpoint-directory> <output-file>
 *   <hostname> and <port> describe the TCP server that Spark Streaming would connect to receive
 *   data. <checkpoint-directory> directory to HDFS-compatible file system which checkpoint data
 *   <output-file> file to which the word counts will be appended
 *
 * <checkpoint-directory> and <output-file> must be absolute paths
 *
 * To run this on your local machine, you need to first run a Netcat server
 *
 *      `$ nc -lk 9999`
 *
 * and run the example as
 *
 *      `$ ./bin/run-example org.apache.spark.examples.streaming.JavaRecoverableNetworkWordCount \
 *              localhost 9999 ~/checkpoint/ ~/out`
 *
 * If the directory ~/checkpoint/ does not exist (e.g. running for the first time), it will create
 * a new StreamingContext (will print "Creating new context" to the console). Otherwise, if
 * checkpoint data exists in ~/checkpoint/, then it will create StreamingContext from
 * the checkpoint data.
 *
 * Refer to the online documentation for more details.
 */
public final class JavaRecoverableNetworkWordCount {
  private static final Pattern SPACE = Pattern.compile(" ");

  private static JavaStreamingContext createContext(String ip,
                                                    int port,
                                                    String checkpointDirectory,
                                                    String outputPath) {

    // If you do not see this printed, that means the StreamingContext has been loaded
    // from the new checkpoint
    System.out.println("Creating new context");
    final File outputFile = new File(outputPath);
    if (outputFile.exists()) {
      outputFile.delete();
    }
    SparkConf sparkConf = new SparkConf().setAppName("JavaRecoverableNetworkWordCount");
    // Create the context with a 1 second batch size
    JavaStreamingContext ssc = new JavaStreamingContext(sparkConf, new Duration(1000));
    ssc.checkpoint(checkpointDirectory);

    // Create a socket stream on target ip:port and count the
    // words in input stream of \n delimited text (eg. generated by 'nc')
    JavaReceiverInputDStream<String> lines = ssc.socketTextStream(ip, port);
    JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
      @Override
      public Iterable<String> call(String x) {
        return Lists.newArrayList(SPACE.split(x));
      }
    });
    JavaPairDStream<String, Integer> wordCounts = words.mapToPair(
      new PairFunction<String, String, Integer>() {
        @Override
        public Tuple2<String, Integer> call(String s) {
          return new Tuple2<String, Integer>(s, 1);
        }
      }).reduceByKey(new Function2<Integer, Integer, Integer>() {
        @Override
        public Integer call(Integer i1, Integer i2) {
          return i1 + i2;
        }
      });

    wordCounts.foreachRDD(new Function2<JavaPairRDD<String, Integer>, Time, Void>() {
      @Override
      public Void call(JavaPairRDD<String, Integer> rdd, Time time) throws IOException {
        String counts = "Counts at time " + time + " " + rdd.collect();
        System.out.println(counts);
        System.out.println("Appending to " + outputFile.getAbsolutePath());
        Files.append(counts + "\n", outputFile, Charset.defaultCharset());
        return null;
      }
    });

    return ssc;
  }

  public static void main(String[] args) {
    if (args.length != 4) {
      System.err.println("You arguments were " + Arrays.asList(args));
      System.err.println(
          "Usage: JavaRecoverableNetworkWordCount <hostname> <port> <checkpoint-directory>\n" +
          "     <output-file>. <hostname> and <port> describe the TCP server that Spark\n" +
          "     Streaming would connect to receive data. <checkpoint-directory> directory to\n" +
          "     HDFS-compatible file system which checkpoint data <output-file> file to which\n" +
          "     the word counts will be appended\n" +
          "\n" +
          "In local mode, <master> should be 'local[n]' with n > 1\n" +
          "Both <checkpoint-directory> and <output-file> must be absolute paths");
      System.exit(1);
    }

    final String ip = args[0];
    final int port = Integer.parseInt(args[1]);
    final String checkpointDirectory = args[2];
    final String outputPath = args[3];
    JavaStreamingContextFactory factory = new JavaStreamingContextFactory() {
      @Override
      public JavaStreamingContext create() {
        return createContext(ip, port, checkpointDirectory, outputPath);
      }
    };
    JavaStreamingContext ssc = JavaStreamingContext.getOrCreate(checkpointDirectory, factory);
    ssc.start();
    ssc.awaitTermination();
  }
}
