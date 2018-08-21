/*
 * Copyright 2016 Skymind
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

package org.deeplearning4j.scalphagozero.models

import org.deeplearning4j.nn.conf.graph.ElementWiseVertex
import org.deeplearning4j.nn.conf.graph.ElementWiseVertex.Op
import org.deeplearning4j.nn.conf.inputs.InputType
import org.deeplearning4j.nn.conf.layers._
import org.deeplearning4j.nn.conf.preprocessor.CnnToFeedForwardPreProcessor
import org.deeplearning4j.nn.conf.{
  ComputationGraphConfiguration,
  ConvolutionMode,
  InputPreProcessor,
  NeuralNetConfiguration
}
import org.deeplearning4j.nn.weights.WeightInit
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.learning.config.Sgd

import scala.collection.JavaConversions._

class DL4JAlphaGoZeroBuilder {

  val conf: ComputationGraphConfiguration.GraphBuilder = new NeuralNetConfiguration.Builder()
    .updater(new Sgd())
    .weightInit(WeightInit.LECUN_NORMAL)
    .graphBuilder() setInputTypes InputType.convolutional(19, 19, 11)

  def addInputs(name: String): Unit =
    conf.addInputs(name)

  def addOutputs(names: List[String]): Unit =
    conf.setOutputs(names: _*)

  def buildAndReturn(): ComputationGraphConfiguration = conf.build()

  def addConvBatchNormBlock(blockName: String,
                            inName: String,
                            nIn: Int,
                            useActivation: Boolean = true,
                            kernelSize: List[Int] = List(3, 3),
                            strides: List[Int] = List(1, 1),
                            convolutionMode: ConvolutionMode = ConvolutionMode.Same): String = {
    val convName = "conv_" + blockName
    val bnName = "batch_norm_" + blockName
    val actName = "relu_" + blockName

    conf.addLayer(
      convName,
      new ConvolutionLayer.Builder()
        .kernelSize(kernelSize: _*)
        .stride(strides: _*)
        .convolutionMode(convolutionMode)
        .nIn(nIn)
        .nOut(256)
        .build(),
      inName
    )

    conf.addLayer(bnName, new BatchNormalization.Builder().nOut(256).build(), convName)
    if (useActivation) {
      conf.addLayer(actName, new ActivationLayer.Builder().activation(Activation.RELU).build(), bnName)
      actName
    } else
      bnName
  }

  def addResidualBlock(blockNumber: Int,
                       inName: String,
                       kernelSize: List[Int] = List(3, 3),
                       strides: List[Int] = List(1, 1),
                       convolutionMode: ConvolutionMode = ConvolutionMode.Same): String = {
    val firstBlock = "residual_1_" + blockNumber
    val firstOut = "relu_residual_1_" + blockNumber
    val secondBlock = "residual_2_" + blockNumber
    val mergeBlock = "add_" + blockNumber
    val actBlock = "relu_" + blockNumber

    val firstBnOut =
      addConvBatchNormBlock(firstBlock, inName, 256, true, kernelSize, strides, convolutionMode)
    val secondBnOut =
      addConvBatchNormBlock(secondBlock, firstOut, 256, false, kernelSize, strides, convolutionMode)
    conf.addVertex(mergeBlock, new ElementWiseVertex(Op.Add), firstBnOut, secondBnOut)
    conf.addLayer(actBlock, new ActivationLayer.Builder().activation(Activation.RELU).build(), mergeBlock)
    actBlock
  }

  def addResidualTower(numBlocks: Int,
                       inName: String,
                       kernelSize: List[Int] = List(3, 3),
                       strides: List[Int] = List(1, 1),
                       convolutionMode: ConvolutionMode = ConvolutionMode.Same): String = {
    var name = inName
    for (i <- 0 until numBlocks)
      name = addResidualBlock(i, name, kernelSize, strides, convolutionMode)
    name
  }

  def addConvolutionalTower(numBlocks: Int,
                            inName: String,
                            kernelSize: List[Int] = List(3, 3),
                            strides: List[Int] = List(1, 1),
                            convolutionMode: ConvolutionMode = ConvolutionMode.Same): Unit = {
    var name = inName
    for (i <- 0 until numBlocks)
      name = addConvBatchNormBlock(i.toString, name, 256, true, kernelSize, strides, convolutionMode)
  }

  def addPolicyHead(inName: String,
                    useActivation: Boolean = true,
                    kernelSize: List[Int] = List(3, 3),
                    strides: List[Int] = List(1, 1),
                    convolutionMode: ConvolutionMode = ConvolutionMode.Same): String = {
    val convName = "policy_head_conv_"
    val bnName = "policy_head_batch_norm_"
    val actName = "policy_head_relu_"
    val denseName = "policy_head_output_"

    conf.addLayer(
      convName,
      new ConvolutionLayer.Builder()
        .kernelSize(kernelSize: _*)
        .stride(strides: _*)
        .convolutionMode(convolutionMode)
        .nOut(2)
        .nIn(256)
        .build(),
      inName
    )
    conf.addLayer(bnName, new BatchNormalization.Builder().nOut(2).build(), convName)
    conf.addLayer(actName, new ActivationLayer.Builder().activation(Activation.RELU).build(), bnName)
    conf.addLayer(denseName, new OutputLayer.Builder().nIn(2 * 19 * 19).nOut(19 * 19 + 1).build(), actName)
    conf.setInputPreProcessors(
      Map[String, InputPreProcessor](denseName -> new CnnToFeedForwardPreProcessor(19, 19, 2))
    )
    denseName
  }
  def addValueHead(inName: String,
                   useActivation: Boolean = true,
                   kernelSize: List[Int] = List(1, 1),
                   strides: List[Int] = List(1, 1),
                   convolutionMode: ConvolutionMode = ConvolutionMode.Same): String = {
    val convName = "value_head_conv_"
    val bnName = "value_head_batch_norm_"
    val actName = "value_head_relu_"
    val denseName = "value_head_dense_"
    val outputName = "value_head_output_"

    conf.addLayer(
      convName,
      new ConvolutionLayer.Builder()
        .kernelSize(kernelSize: _*)
        .stride(strides: _*)
        .convolutionMode(convolutionMode)
        .nOut(1)
        .nIn(256)
        .build(),
      inName
    )
    conf.addLayer(bnName, new BatchNormalization.Builder().nOut(1).build(), convName)
    conf.addLayer(actName, new ActivationLayer.Builder().activation(Activation.RELU).build(), bnName)
    conf.addLayer(denseName, new DenseLayer.Builder().nIn(19 * 19).nOut(256).build(), actName)
    conf.setInputPreProcessors(
      Map[String, InputPreProcessor](denseName -> new CnnToFeedForwardPreProcessor(19, 19, 1))
    )
    conf.addLayer(outputName, new OutputLayer.Builder().nIn(256).nOut(1).build(), denseName)
    outputName
  }

}
