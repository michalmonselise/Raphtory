package com.raphtory.Actors.RaphtoryActors.DataSource

import akka.actor.Timers
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.util.Timeout
import akka.pattern.ask

import spray.json._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import com.raphtory.Actors.RaphtoryActors.RaphtoryActor
import com.raphtory.caseclass.{ClusterStatusRequest, ClusterStatusResponse, RaphCaseClass, Command}
import com.raphtory.utils.CommandEnum
import kamon.Kamon

import scala.concurrent.ExecutionContext.Implicits.global

trait UpdaterTrait extends RaphtoryActor with Timers {
  import com.raphtory.caseclass.RaphtoryJsonProtocol._
  private var currentMessage  = 0
  private var previousMessage = 0
  private var safe            = false
  private var counter         = 0

  protected final val mediator = DistributedPubSub(context.system).mediator
  mediator ! DistributedPubSubMediator.Put(self)

  override def preStart() {
    context.system.scheduler.schedule(Duration(7, SECONDS), Duration(1, SECONDS), self,"benchmark")
    context.system.scheduler.schedule(Duration(7, SECONDS), Duration(1, SECONDS), self,"stateCheck")
  }

  protected def sendCommand[T <: RaphCaseClass](command: CommandEnum.Value, value: T) = {
    counter       += 1
    currentMessage+=1
    Kamon.counter("raphtory.updateGen.commandsSent").increment()
    kGauge.refine("actor" -> "Updater", "name" -> "updatesSentGauge").set(counter)
    val jsonCommand = Command(command, value).toJson.toString
    mediator ! DistributedPubSubMediator.Send("/user/router", jsonCommand, false)
  }

  protected def processChildMessages(rcvdMessage : Any)
  protected def running()

  final protected def isSafe() = safe

  final override def receive : Receive = {
    case "stateCheck" => checkUp()
    case "benchmark" => benchmark()
    case other : Any => processChildMessages(other)
  }

  private def benchmark() : Unit = {
    val diff = currentMessage - previousMessage
    previousMessage = currentMessage
    counter = 0
    kGauge.refine("actor" -> "Updater", "name" -> "diff").set(diff)
  }

  private def checkUp() : Unit = {
    if(!safe) {
      try {
        implicit val timeout: Timeout = Timeout(10 seconds)
        val future = mediator ? DistributedPubSubMediator.Send("/user/WatchDog", ClusterStatusRequest, false)
        safe = Await.result(future, timeout.duration).asInstanceOf[ClusterStatusResponse].clusterUp
      } catch {
        case e: java.util.concurrent.TimeoutException => {
          safe = false
        }
      }
    }
  }
}
