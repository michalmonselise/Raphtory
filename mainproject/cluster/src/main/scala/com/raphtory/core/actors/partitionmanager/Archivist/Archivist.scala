package com.raphtory.core.actors.partitionmanager.Archivist

import java.util.concurrent.Executors

import akka.actor.Props
import ch.qos.logback.classic.Level
import com.raphtory.core.actors.RaphtoryActor
import com.raphtory.core.actors.partitionmanager.Archivist.Helpers.{ArchivingSlave, CompressionSlave}
import com.raphtory.core.model.communication._
import com.raphtory.core.model.graphentities._
import com.raphtory.core.storage.{EntityStorage, RaphtoryDBWrite}
import com.raphtory.core.utils.KeyEnum
import monix.eval.Task
import monix.execution.ExecutionModel.AlwaysAsyncExecution
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.parallel.mutable.ParTrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
//TODO decide how to do shrinking window as graph expands
//TODO work out general cutoff function
//TODO don't resave history
//TODO fix edges
//TODO implement temporal/spacial profiles (future)
//TODO join historian to cluster



class Archivist(maximumMem:Double) extends RaphtoryActor {
  val compressing    : Boolean =  System.getenv().getOrDefault("COMPRESSING", "true").trim.toBoolean
  val saving    : Boolean =  System.getenv().getOrDefault("SAVING", "true").trim.toBoolean
  println(s"Archivist compressing = $compressing, Saving = $saving")

  //Turn logging off
  //val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
  //root.setLevel(Level.ERROR)

  //get the runtime for memory usage
  val runtime = Runtime.getRuntime
  //times to track how long compression and archiving takes
  var vertexCompressionTime:Long  = 0L
  var edgeCompressionTime:Long    = 0L
  var totalCompressionTime:Long   = 0L
  var vertexArchiveTime:Long      = 0L
  var edgeArchiveTime:Long        = 0L
  var totalArchiveTime:Long       = 0L
  // bools to decide when to swap between compressing and archiving
  var vertexCompressionFinished  = false
  var edgeCompressionFinished   = false
  var vertexArchivingFinished    = false
  var edgeArchivingFinished     = false
  // percent of history to be compressed/archived
  var compressionPercent        = 90f
  var archivePercentage         = 10f
  // vars for the latest point the graph is saved to
  var lastSaved                 = 0l
  var newLastSaved              = 0l
  //var for the new oldest point after archiving
  var removePointGlobal:Long      = 0L
  var removalPoint:Long           = 0L
  // children for distribution of compresssion and archiving
  val edgeCompressor   =  context.actorOf(Props[CompressionSlave],"edgecompressor");
  val vertexCompressor =  context.actorOf(Props[CompressionSlave],"vertexcompressor");
  val edgeArchiver     =  context.actorOf(Props[ArchivingSlave],"edgearchiver");
  val vertexArchiver   =  context.actorOf(Props[ArchivingSlave],"vertexarchiver");


  override def preStart() {
    edgeCompressor   ! SetupSlave(5) //bring the slaves of all components online
    vertexCompressor ! SetupSlave(5)
    edgeArchiver     ! SetupSlave(5)
    vertexArchiver   ! SetupSlave(5)
    context.system.scheduler.scheduleOnce(20.seconds, self,"compress") //start the compression process in 20 seconds
  }

  override def receive: Receive = {
    case "compress"                               => compressGraph()
    case "archive"                                => archiveGraph()
    case FinishedEdgeCompression(total)           => compressEnder("edge")
    case FinishedVertexCompression(total)         => compressEnder("vertex")
    case FinishedEdgeArchiving(total,archived)    => archiveEnder("edge",archived)
    case FinishedVertexArchiving(total,archived)  => archiveEnder("vertex",archived)
  }

  def compressGraph() : Unit = {
    newLastSaved = cutOff(true) //get the cut off boundry for 90% of history in meme
    edgeCompressor   ! CompressEdges(newLastSaved) //forward compression request to children
    vertexCompressor ! CompressVertices(newLastSaved)
  }

  def compressEnder(name:String): Unit = {
    if(name equals("edge")){ //if the edge is finished, report this to the user and save the result
      println(s"finished $name compressing in ${(System.currentTimeMillis()-edgeCompressionTime)/1000} seconds")
      edgeCompressionFinished = true
    }
    if(name equals("vertex")){ // if the vertices are finished, save this and report it to the user
      println(s"finished $name compressing in ${(System.currentTimeMillis()-vertexCompressionTime)/1000}seconds")
      vertexCompressionFinished = true
    }
    if(edgeCompressionFinished && vertexCompressionFinished){ //if both are finished
      println(s"finished total compression in ${(System.currentTimeMillis()-totalCompressionTime)/1000} seconds") //report this to the user
      lastSaved = newLastSaved
      EntityStorage.lastCompressedAt = lastSaved //update the saved vals so we know where we are compressed up to
      vertexCompressionFinished = false //reset the compression vars
      edgeCompressionFinished = false
      context.system.scheduler.scheduleOnce(5.millisecond, self, "archive") //start the archiving process
    }
  }

  def archiveGraph() : Unit = {
    println("Try to archive")
    if(!spaceForExtraHistory) { //check if we need to archive
      removalPoint = cutOff(false) // get the cut off for 10% of the compressed history
      edgeArchiver ! ArchiveEdges(removalPoint) //send the archive request to the children
      vertexArchiver ! ArchiveVertices(removalPoint)
    }
    else {
      context.system.scheduler.scheduleOnce(5.millisecond, self,"compress") //if we are not archiving start the compression process again
    }
  }

  def archiveEnder(name:String,archived:(Int,Int,Int)): Unit = {
    if(name equals("edge")){
      println(s"finished $name archiving in ${(System.currentTimeMillis()-edgeArchiveTime)/1000} seconds")
      println(s"${archived._2} History points removed, ${archived._1} Property points removed, ${archived._3} Full Edges removed")
      edgeArchivingFinished = true
    }
    if(name equals("vertex")){
      println(s"finished $name archiving in ${(System.currentTimeMillis()-vertexArchiveTime)/1000}seconds")
      println(s"${archived._2} History points removed, ${archived._1} Property points removed, ${archived._3} Full Vertices removed")
      vertexArchivingFinished = true
    }

    if (edgeArchivingFinished && vertexArchivingFinished) {
      vertexArchivingFinished = false
      edgeArchivingFinished = false
      context.system.scheduler.scheduleOnce(5.millisecond, self, "archiveCheck")
      println(s"finished total archiving in ${(System.currentTimeMillis()-totalArchiveTime)/1000} seconds")
      EntityStorage.oldestTime = removePointGlobal
      context.system.scheduler.scheduleOnce(10.millisecond, self, "compress") //restart archive to check if there is now enough space
    }

  }

  def spaceForExtraHistory = {
    val factor = 2
    val totalMemory = runtime.maxMemory
    val freeMemory = runtime.freeMemory
    val usedMemory = (totalMemory - freeMemory)
    val total = usedMemory/(totalMemory/factor).asInstanceOf[Float]
    //println(s"max ${runtime.maxMemory()} total ${runtime.totalMemory()} diff ${runtime.maxMemory()-runtime.totalMemory()} ")
    println(s"Memory usage at ${total*100}% of ${totalMemory/(1024*1024*factor)}MB")
    if(total < (1-maximumMem)) true else false
  } //check if used memory less than set maximum

  def toCompress(newestPoint:Long,oldestPoint:Long):Long =  (((newestPoint-oldestPoint) / 100f) * compressionPercent).asInstanceOf[Long]
  def toArchive(newestPoint:Long,oldestPoint:Long):Long =  (((newestPoint-oldestPoint) / 100f) * archivePercentage).asInstanceOf[Long]
  def cutOff(compress:Boolean) = {
    val oldestPoint = EntityStorage.oldestTime
    val newestPoint = EntityStorage.newestTime
    setActionTime(compress)
    println(s" Difference between oldest $oldestPoint to newest point $newestPoint --- ${((newestPoint-oldestPoint)/1000)}, ${(toCompress(newestPoint,oldestPoint))/1000} seconds compressed")
    if(oldestPoint != Long.MaxValue) {
      if (compress) oldestPoint + toCompress(newestPoint, oldestPoint) //oldestpoint + halfway to the newest point == always keep half of in memory stuff compressed
      else oldestPoint + toArchive(newestPoint, oldestPoint)
    }
    else newestPoint
  }
  def setActionTime(CorA:Boolean) ={
    if(CorA){
      vertexCompressionTime = System.currentTimeMillis()
      edgeCompressionTime = vertexCompressionTime
      totalCompressionTime = vertexCompressionTime
    }
    else{
      vertexArchiveTime = System.currentTimeMillis()
      edgeArchiveTime = vertexArchiveTime
      totalArchiveTime = vertexArchiveTime
    }
  }

}








//export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_162.jdk/Contents/Home/
//JAVA_OPTS=-XX:+UseConcMarkSweepGC -XX:+DisableExplicitGC -XX:+UseParNewGC -Xms10g -Xmx10g -XX:NewRatio=3