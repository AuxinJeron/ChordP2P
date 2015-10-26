/**
 * Created by leon on 10/23/15.
 */

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import akka.event.Logging
import scala.concurrent.{ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global
import scala.util.Random
import akka.util.Timeout
import scala.concurrent.duration.Duration

case class InsertNodes(nodesNum: Int, requestsNum: Int)
case class InvokeNodes()
case class NodeEnd(nodeRef: ActorRef, nodeIp: String, nodeId: BigInt, averageHops: Double)

object Chord {
  def main (args: Array[String]): Unit = {
//    println("Hash Value is " + hashManager.getHashInt("leon.li@ufl.edu"))
    if (args.length != 2) {
      println("Invalid arguments, please input arguments as the format <numNodes> <numRequests>")
      return
    }
    val system = ActorSystem("ChordSystem")
    val manager = system.actorOf(Props(new Manager()), "Manager")
    manager ! InsertNodes(args(0).toInt, args(1).toInt)
  }
}

class Manager extends Actor {
  var nodes = 0
  var endNodes = 0
  var timeout: Timeout = new Timeout(Duration.create(5000, "milliseconds"))
  val log = Logging(context.system, this)
  var requestsNum = 0
  var averageHops: Double = 0

  def insertNodes(nodesNum: Int, requestsNum: Int) = {
    println("=========================================================")
    println("begin to insert nodes")
    println("=========================================================")
    this.requestsNum = requestsNum
    for (i <- 0 until nodesNum) {
      val ipAddress = String.format("node" + i.toString)
      log.info(s"create $ipAddress")
      nodes += 1
      val node = context.actorOf(Props(new Node(ipAddress, requestsNum)), name = ipAddress)
      if (i != 0) {
        val helpNodeIp = String.format("node" + (Random.nextInt() % i).abs)
        log.info(s"helpNodeIp is $helpNodeIp")
        implicit val timeout = Timeout(Duration.create(2, "seconds"))
        val path = String.format(s"/user/Manager/$helpNodeIp")
        val future: Future[Object] = context.system.actorSelection(path).resolveOne()(timeout)
        val helpNode = Await.result(future, timeout.duration).asInstanceOf[ActorRef]
        node ! Join(helpNode)
      }
      else {
        node ! Join(node)
      }
    }

    println("=========================================================")
    println("begin to send messages")
    println("=========================================================")
    context.system.scheduler.scheduleOnce(Duration.create(2, "seconds"), self, InvokeNodes())
  }

  def invokeNodes() = {
    for (i <- 0 until nodes) {
      val node = context.actorSelection("/user/Manager/node" + i)
      val message = messageGenerater.randomMessage(20)
      node ! SendMessage(message, this.requestsNum)
    }
  }

  def receive = {
    case InsertNodes(nodesNum: Int, requestsNum: Int) =>
      this.insertNodes(nodesNum, requestsNum)
    case NodeEnd(nodeRef: ActorRef, nodeIp: String, nodeId: BigInt, averageHops: Double) =>
      this.averageHops = (this.averageHops * this.endNodes + averageHops) / (this.endNodes + 1)
      this.endNodes += 1
      if (this.endNodes == nodes) {
        println("the average num of hops is " + this.averageHops)
        context.system.shutdown()
      }
    case InvokeNodes() =>
      this.invokeNodes()
  }
}


