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

package com.ibm.cds.spark.samples

import scala.collection.mutable._
import com.ibm.pixiedust.ChannelReceiver
import org.apache.spark.Logging
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.spark.SparkContext
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.Row
import com.ibm.cds.spark.samples.config.DemoConfig
import org.apache.spark.streaming.Seconds
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.types.DoubleType
import org.http4s.client.blaze.PooledHttp1Client
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StringType
import com.google.common.base.CharMatcher
import com.ibm.couchdb.CouchDb
import com.ibm.couchdb.TypeMapping
import com.ibm.couchdb.CouchDbApi
import org.apache.spark.sql.SQLContext
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverError
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStopped
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted
import org.apache.spark.streaming.scheduler.StreamingListenerBatchCompleted
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.HashPartitioner
import twitter4j.Status
import org.codehaus.jettison.json.JSONObject

/* @author dtaieb
 * Twitter+Watson sentiment analysis app powered by Pixiedust
 */
object PixiedustStreamingTwitter extends ChannelReceiver() with Logging{
  var ssc: StreamingContext = null
  var sqlContext: SQLContext = null
  var workingRDD: RDD[Row] = null
  var schemaTweets : StructType = null
  //Hold configuration key/value pairs
  lazy val config = new DemoConfig
  lazy val logger: Logger = Logger.getLogger( "com.ibm.cds.spark.samples.PixiedustStreamingTwitter" )
  
  val BEGINSTREAM = "@BEGINSTREAM@"
  val ENDSTREAM = "@ENDSTREAM@"
  
  def sendLog(s:String){
    send("log", s)
  }
  
  //Wrapper api for Notebook access
  def setConfig(key:String, value:String){
    config.setConfig(key, value)
  }
  
  //main method invoked when running as a standalone Spark Application
  def main(args: Array[String]) {    
    val conf = new SparkConf().setAppName("Pixiedust Spark Streaming Twitter Demo")
    val sc = new SparkContext(conf)
    startStreaming();
  }
  
  class PixiedustStreamingListener extends org.apache.spark.streaming.scheduler.StreamingListener {
      override def onReceiverStarted(receiverStarted: StreamingListenerReceiverStarted) { 
        sendLog("Receiver Started: " + receiverStarted.receiverInfo.name )
        //Signal the frontend that we started streaming
        sendLog(BEGINSTREAM)
      }
    
      override def onReceiverError(receiverError: StreamingListenerReceiverError) { 
        sendLog("Receiver Error: " + receiverError.receiverInfo.lastError)
      }
    
      override def onReceiverStopped(receiverStopped: StreamingListenerReceiverStopped) { 
        sendLog("Receiver Stopped: " + receiverStopped.receiverInfo.name)
        sendLog("Reason: " + receiverStopped.receiverInfo.lastError + " : " + receiverStopped.receiverInfo.lastErrorMessage)
        //signal the front end that we're done streaming
        sendLog(ENDSTREAM)
      }
      
      override def onBatchStarted(batchStarted: StreamingListenerBatchStarted){
        sendLog("Batch started with " + batchStarted.batchInfo.numRecords + " records")
      }
      
      override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted){
        sendLog("Batch completed with " + batchCompleted.batchInfo.numRecords + " records");
      }
  }
  
  val reuseCheckpoint = false;
  
  def startStreaming(){
    val sc = SparkContext.getOrCreate
    sendLog("Starting twitter stream");
    if ( ssc != null ){
      sendLog("Twitter Stream already running");
      sendLog("Please use stopTwitterStreaming() first and try again");
      return;
    }
    
    if ( !config.validateConfiguration() ){
      sendLog("Unable to validate config")
      sendLog(ENDSTREAM)
      return;
    }
    
    Logger.getLogger("org.apache.spark").setLevel(Level.OFF)
    
    //Set the hadoop configuration if needed
    val checkpointDir = config.getConfig( DemoConfig.CHECKPOINT_DIR_KEY );
    if ( checkpointDir.startsWith("swift") ){
      println("Setting hadoop configuration for swift container")
      config.set_hadoop_config(sc)
    }
    
    workingRDD = sc.emptyRDD
    
    if ( !reuseCheckpoint ){
      ssc = createStreamingContextAndRunAnalytics(sc);
    }else{
      ssc = StreamingContext.getOrCreate( 
          config.getConfig( DemoConfig.CHECKPOINT_DIR_KEY ), 
          () => {
            createStreamingContextAndRunAnalytics(sc);
          },
          sc.hadoopConfiguration,
          true
      );
    }
    
    ssc.addStreamingListener( new PixiedustStreamingListener )
    
    ssc.start()
    
    sendLog("Twitter stream started");   
    
/**    
    
    //Broadcast the config to each worker node
    val broadcastVar = sc.broadcast(config.toImmutableMap)
    
    var canStopTwitterStream = true
    var batchesProcessed=0
    
    ssc = new StreamingContext( sc, Seconds(5) )
    
    
    
    try{
      sqlContext = new SQLContext(sc)
      val keys = config.getConfig("tweets.key").split(",");
      var stream = org.apache.spark.streaming.twitter.TwitterUtils.createStream( ssc, None );
      
      if ( schemaTweets == null ){
        val schemaString = "author date lang text lat:double long:double"
        schemaTweets =
          StructType(
            schemaString.split(" ").map(
              fieldName => {
                val ar = fieldName.split(":")
                StructField(
                    ar.lift(0).get, 
                    ar.lift(1).getOrElse("string") match{
                      case "int" => IntegerType
                      case "double" => DoubleType
                      case _ => StringType
                    },
                    true)
              }
            ).union( 
                ToneAnalyzer.sentimentFactors.map( f => StructField( f._1, DoubleType )).toArray[StructField]
            )
          )
      }
      val tweets = stream.filter { status => 
        Option(status.getUser).flatMap[String] { 
          u => Option(u.getLang) 
        }.getOrElse("").startsWith("en") && CharMatcher.ASCII.matchesAllOf(status.getText) && ( keys.isEmpty || keys.exists{status.getText.contains(_)})
      }
      
      lazy val client = PooledHttp1Client()
      val rowTweets = tweets.map(status=> {
        val sentiment = ToneAnalyzer.computeSentiment( client, status, broadcastVar )
        
        var colValues = Array[Any](
          status.getUser.getName, //author
          status.getCreatedAt.toString,   //date
          status.getUser.getLang,  //Lang
          status.getText,               //text
          Option(status.getGeoLocation).map{ _.getLatitude}.getOrElse(0.0),      //lat
          Option(status.getGeoLocation).map{_.getLongitude}.getOrElse(0.0)    //long
          //exception
        )
        
        var scoreMap : Map[String, Double] = Map()
        if ( sentiment != null ){
          for( toneCategory <- Option(sentiment.tone_categories).getOrElse( Seq() )){
            for ( tone <- Option( toneCategory.tones ).getOrElse( Seq() ) ){
              scoreMap.put( tone.tone_id, tone.score )
            }
          }
        }
             
        colValues = colValues ++ ToneAnalyzer.sentimentFactors.map { f => (BigDecimal(scoreMap.get(f._2).getOrElse(0.0)).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble) * 100.0  }
        send("tweets", "{\"author\": \"" + status.getUser.getName + "\", \"text\":\"bla\", \"sentiment\":\"sadness\" }")
        //Return [Row, (sentiment, status)]
        (Row(colValues.toArray:_*),(sentiment, status))
      })

      rowTweets.foreachRDD( rdd => {
        if(batchesProcessed==0){
          canStopTwitterStream=false
        }
        try{
          if( rdd.count > 0 ){
            batchesProcessed += 1
            workingRDD = sc.parallelize( rdd.map( t => t._1 ).collect()).union( workingRDD )
          }
        }catch{
          case e: InterruptedException=>//Ignore
          case e: Exception => sendLog(e.getMessage)
        }finally{
            canStopTwitterStream = true
        }        
      })

    }catch{
      case e : Exception => sendLog(e.getMessage )
      return
    }
    
    */
  }
  
  def stopStreaming(){
    if ( ssc == null){
      sendLog("No Twitter stream to stop");
      return;
    }
    
    sendLog("Stopping Twitter stream. Please wait this may take a while")
    ssc.stop(stopSparkContext = false, stopGracefully = false)
    ssc = null
    sendLog("Twitter stream stopped");
  }
  
  def createStreamingContextAndRunAnalytics(sc:SparkContext):StreamingContext={
    //Broadcast the config to each worker node
    val broadcastVar = sc.broadcast( config.toImmutableMap )
    ssc = new StreamingContext( sc, Seconds(5) )
    ssc.checkpoint(config.getConfig( DemoConfig.CHECKPOINT_DIR_KEY ));
    val stream = org.apache.spark.streaming.twitter.TwitterUtils.createStream( ssc, None );
    runAnalytics(sc, broadcastVar, stream)
    ssc;
  }
  
  case class EnrichedTweet( author:String, date: String, lang: String, text: String, lat: Double, long: Double, sentimentScores: Map[String, Double])
  
  def runAnalytics(sc:SparkContext, broadcastVar: Broadcast[scala.collection.immutable.Map[String,String]], stream:DStream[Status]){
    val keys = broadcastVar.value.get("tweets.key").get.split(",");
    val tweets = stream.filter { status => 
      Option(status.getUser).flatMap[String] { 
        u => Option(u.getLang) 
      }.getOrElse("").startsWith("en") && CharMatcher.ASCII.matchesAllOf(status.getText) && ( keys.isEmpty || keys.exists{key => status.getText.toLowerCase.contains(key.toLowerCase)})
    }
    
    val rowTweets = tweets.map(status=> {
      lazy val client = PooledHttp1Client()
      val sentiment = ToneAnalyzer.computeSentiment( client, status, broadcastVar )
      var scoreMap : Map[String, Double] = Map()
      if ( sentiment != null ){
        for( toneCategory <- Option(sentiment.tone_categories).getOrElse( Seq() )){
          for ( tone <- Option( toneCategory.tones ).getOrElse( Seq() ) ){
            scoreMap.put( tone.tone_id, (BigDecimal(tone.score).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble) * 100.0 )
          }
        }
      }
      
      var jsonSentiment="{";
      scoreMap.foreach( t => jsonSentiment = jsonSentiment + (if (jsonSentiment.length() == 1) "" else ",") + "\"" + t._1 + "\":" + t._2)
      jsonSentiment += "}"
      send("tweets", "{\"author\": \"" + status.getUser.getName + "\", \"pic\":\"" + status.getUser.getOriginalProfileImageURLHttps + "\",\"text\":" + JSONObject.quote( status.getText ) 
          + ", \"sentiment\": " + jsonSentiment + "}")
      
      EnrichedTweet( 
          status.getUser.getName,
          status.getCreatedAt.toString,          
          status.getUser.getLang,
          status.getText,
          Option(status.getGeoLocation).map{ _.getLatitude}.getOrElse(0.0),
          Option(status.getGeoLocation).map{ _.getLongitude}.getOrElse(0.0),          
          scoreMap
      )
    })
   
   val delimTagTone = "-%!"
   val delimToneScore = ":%@"
   val statsStream = rowTweets.map { eTweet => ("total_tweets", 1L) }
      .reduceByKey( _+_ )
      .updateStateByKey( (a:scala.collection.Seq[Long], b:Option[Long] ) => {
        var runningCount=b.getOrElse(0L)
        a.foreach { v => runningCount=runningCount+v }
        Some(runningCount)
      })
   statsStream.foreachRDD( rdd =>{
     send("TweetProcessed", TweetsMetricJsonSerializer.serialize(rdd.collect()))
   })
   
   val metricsStream = rowTweets.flatMap { eTweet => {
     val retList = ListBuffer[String]()
     for ( tag <- eTweet.text.split("\\s+") ){
       if ( tag.startsWith( "#") && tag.length > 1 ){
           for ( tone <- Option( eTweet.sentimentScores.keys ).getOrElse( Seq() ) ){
               retList += (tag + delimTagTone + tone + delimToneScore + eTweet.sentimentScores.getOrElse( tone, 0.0))
           }
       }
     }
     retList.toList
   }}
   .map { fullTag => {
       val split = fullTag.split(delimToneScore);
       (split(0), split(1).toFloat) 
   }}
   .combineByKey( 
       (x:Float) => (x,1), 
       (x:(Float,Int), y:Float) => (x._1 + y, x._2+1), 
       (x:(Float,Int),y:(Float,Int)) => (x._1 + y._1, x._2 + y._2),
       new HashPartitioner(sc.defaultParallelism)
   )
   .map[(String,(Long/*count*/, List[(String, Double)]))]{ t => {
     val key = t._1;
     val ab = t._2;
     val split = key.split(delimTagTone)
     (split(0), (ab._2, List((split(1), BigDecimal(ab._1/ab._2).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble ))))
   }}
   .reduceByKey( (t,u) => (t._1+u._1, (t._2 ::: u._2).sortWith( (l,r) => l._1.compareTo( r._1 ) < 0 )))
   .mapValues( (item:(Long, List[(String,Double)])) => {
     val unzip = item._2.unzip
     (item._1/(item._2.size), unzip._1, unzip._2)
   })
   .updateStateByKey( (a:scala.collection.Seq[(Long, List[String], List[Double])], b: Option[(Long, List[String], List[Double])]) => {
     val safeB = b.getOrElse( (0L, List(), List() ) )
     var listTones = safeB._2
     var listScores = safeB._3
     var count = safeB._1
     for( item <- a ){
       count += item._1
       listScores = listScores.zipAll( item._3, 0.0, 0.0).map{ case(a,b)=>(a+b)/2 }.toList
       listTones = item._2
     }
     
     Some( (count, listTones, listScores) )
   })
   
   metricsStream.print
   
   metricsStream.foreachRDD( rdd =>{
     val topHashTags = rdd.sortBy( f => f._2._1, false ).take(5)
     if ( !topHashTags.isEmpty){
         send("topHashtags", TweetsMetricJsonSerializer.serialize(topHashTags.map( f => (f._1, f._2._1 ))))
         send("toneScores", ToneScoreJsonSerializer.serialize(topHashTags))
     }
   })
  }
}