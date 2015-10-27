import java.security.MessageDigest

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import scala.concurrent.{ExecutionContext, Future, Await}
import ExecutionContext.Implicits.global
import akka.pattern.Patterns
import akka.util.Timeout

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Random}
import scala.util.control.Breaks._

class NodeBonus(ipAddress: String, requestsNum: Int) extends Actor {
  val identifier: BigInt = hashManager.getHashInt(ipAddress) % (BigInt(2).pow(hashManager.consistentM))
  var sentMessages = 0
  var fingerTable: Array[Finger] = Array.fill[Finger](hashManager.consistentM + 1)(new Finger())
  var successorRef: ActorRef = self
  var successorId = identifier
  var predecessorRef: ActorRef = self
  var predecessorId = identifier
  var averagehops: Double = 0
  val log = Logging(context.system, this)

  for (i <- 1 until fingerTable.length) {
    fingerTable(i).start = (this.identifier + BigInt(2).pow(i - 1)) % BigInt(2).pow(hashManager.consistentM)
    val nextStart = (this.identifier + BigInt(2).pow(i)) % BigInt(2).pow(hashManager.consistentM)
    fingerTable(i).interval = new ChordRange(fingerTable(i).start, nextStart)
    fingerTable(i).nodeRef = self
    fingerTable(i).nodeId = this.identifier
  }

  def findSuccessor(reqNode: ActorRef, id: BigInt, hopsNum: Int) = {
    var outRange = false
    val start = this.identifier
    val end = this.successorId
    if (end > start && (id <= start || id > end)) outRange = true
    if (end <= start && id <= start && id > end) outRange = true

    if (outRange) {
      val nodeRef = this.closestPreFinger(id)
      nodeRef ! FindSuccessor(reqNode, id, hopsNum + 1)
    }
    else {
      reqNode ! FoundSuccessor(id, this.successorId, hopsNum)
    }
  }

  def foundSuccessor(id: BigInt, successorId: BigInt, hopsNum: Int) = {
    log.info(this.ipAddress + " used " + hopsNum + " hops to find successor for " + id)
  }

  def findUpdateTrail(reqNode:ActorRef, reqNodeId: BigInt, index: Int, hopsNum: Int) = {
    var outRange = false
    val start = this.identifier
    val end = this.successorId
    var id = reqNodeId - BigInt(2).pow(index - 1)
    if (id < 0) {
      id += BigInt(2).pow(hashManager.consistentM)
    }

    if (end > start && (id <= start || id > end)) outRange = true
    if (end <= start && id <= start && id > end) outRange = true
    log.info(s"the candidate identifier is $id start $start end $end outRange $outRange")

    if (outRange) {
      val nodeRef = this.closestPreFinger(id)
      nodeRef ! FindUpdateTrail(reqNode, reqNodeId, index, hopsNum + 1)
    }
    else {
      log.info(s"predecessor of $id is " + this.identifier)
      self ! UpdateFingerTable(reqNode, reqNodeId, index)
    }
  }

  def updateFingerTable(nodeRef:ActorRef, nodeId: BigInt, index: Int): Unit = {
    if (nodeRef == self) return // the node should not affect self

    var inRange = false
    val start = this.identifier
    val end = this.fingerTable(index).nodeId
    if (end > start && nodeId >= start && nodeId < end) inRange = true
    if (end <= start && (nodeId >= start || nodeId < end)) inRange = true

    log.info("start " + start + " end " + end + " nodeId " + nodeId + " with inRange " + inRange)
    if (inRange) {
      this.fingerTable(index).nodeId = nodeId
      this.fingerTable(index).nodeRef = nodeRef
      if (index == 1) {
        this.successorId = nodeId
        this.successorRef = nodeRef
      }
      this.predecessorRef ! UpdateFingerTable(nodeRef, nodeId, index)
    }
  }

  def findFinger(reqNode: ActorRef, helpNode: ActorRef, index: Int, id: BigInt, hopsNum: Int) = {
    var outRange = false
    val start = this.identifier
    val end = this.successorId
    if (end > start && (id <= start || id > end)) outRange = true
    if (end <= start && id <= start && id > end) outRange = true

    if (outRange) {
      val nodeRef = this.closestPreFinger(id)
      nodeRef ! FindFinger(reqNode, helpNode, index, id, hopsNum)
    }
    else {
      reqNode ! FoundFinger(helpNode, index, this.successorRef, this.successorId, hopsNum + 1)
    }
  }

  def foundFinger(helpNode: ActorRef, index: Int, nodeRef: ActorRef, nodeId: BigInt, hopsNum: Int) = {
    //log.info(helpNode + s" used $hopsNum hops to find finger($index) $nodeId")
    this.fingerTable(index).nodeId = nodeId
    this.fingerTable(index).nodeRef = nodeRef

    var outRange = false
    if (this.identifier > this.fingerTable(index).start && (nodeId <= this.fingerTable(index).start ||
      nodeId > this.identifier)) outRange = true
    if (this.identifier <= this.fingerTable(index).start && nodeId <= this.fingerTable(index).start &&
      nodeId > this.identifier) outRange = true

    //log.info(this.identifier + " start " + this.fingerTable(index).start + " nodeId " + nodeId + s" outRange $outRange")

    if (outRange) {
      this.fingerTable(index).nodeId = this.identifier
      this.fingerTable(index).nodeRef = self
    }

    if (index == 1) {
      this.successorId = this.fingerTable(1).nodeId
      this.successorRef = this.fingerTable(1).nodeRef
      this.successorRef ! GetPredecessor(self)
      this.successorRef ! SetPredecessor(this.predecessorId, this.predecessorRef)
    }

    breakable {
      for (i <- index to hashManager.consistentM - 1) {
        var inRange = false
        val start = this.identifier
        val end = this.fingerTable(i).nodeId
        val nodeId = this.fingerTable(i + 1).start
        if (end > start && nodeId >= start && nodeId < end) inRange = true
        if (end <= start && (nodeId >= start || nodeId < end)) inRange = true
        //log.info(s"find finger($i) with start $start end $end nodeId $nodeId inRange $inRange")
        if (inRange) {
          this.fingerTable(i + 1).nodeId = this.fingerTable(i).nodeId
          this.fingerTable(i + 1).nodeRef = this.fingerTable(i).nodeRef
        }
        else {
          helpNode ! FindFinger(self, helpNode, i + 1, this.fingerTable(i + 1).start, 0)
          break()
        }
        if (i == hashManager.consistentM - 1) {
          this.updateOthers()
        }
      }
    }
  }

  def join(helpNode: ActorRef) = {
    println("insert " + this.ipAddress + " with the help of " + helpNode)
    //log.info(this.ipAddress + " " + this.identifier + " join with the helpNode " + helpNode)
    if (helpNode != self) {
      this.initFingerTable(helpNode)
    }
    else {
      //this.printFingerTable()
      //this.sendMessage("leon.li@ufl.edu", this.requestsNum)
    }
  }

  def initFingerTable(helpNode: ActorRef) = {
    helpNode ! FindFinger(self, helpNode, 1, this.fingerTable(1).start, 0)
  }

  def closestPreFinger(id: BigInt): ActorRef = {
    for (i <- hashManager.consistentM to (1, -1)) {
      var inRange = false
      val start = this.identifier
      val end = id
      val nodeId = fingerTable(i).nodeId
      if (start < end && nodeId > start && nodeId < end) inRange = true
      if (start >= end && (nodeId > start || nodeId < end)) inRange = true
      if (inRange) {
        return fingerTable(i).nodeRef
      }
    }
    return self
  }

  def getPredecessor(reqNode: ActorRef) = {
    reqNode ! SetPredecessor(this.predecessorId, this.predecessorRef)
  }

  def setPredecessor(predecessorId: BigInt, predecessorRef: ActorRef) = {
    this.predecessorId = predecessorId
    this.predecessorRef = predecessorRef
  }

  def updateOthers() = {
    for (i <- 1 to hashManager.consistentM) {
      self ! FindUpdateTrail(self, this.identifier, i, 0)
    }
  }

  def sendMessage(message: String, requestsNum: Int) = {
    if (requestsNum > 0) {
      val identifier = hashManager.getHashInt(message) % BigInt(2).pow(hashManager.consistentM)
      log.info(s"send message with identifier $identifier")
      self ! searchMessageId(self, identifier, 0)
      val newMessage = messageGenerater.randomMessage(20)
      context.system.scheduler.scheduleOnce(Duration.create(10, "milliseconds"), self, SendMessage(newMessage, requestsNum - 1))
    }
  }

  def searchMessageId(reqNode: ActorRef, id: BigInt, hopsNum: Int) = {
    var outRange = false
    val start = this.identifier
    val end = this.successorId
    if (end > start && (id <= start || id > end)) outRange = true
    if (end <= start && id <= start && id > end) outRange = true

    log.info(s"search messageId $id start $start end $end")

    if (outRange) {
      val nodeRef = this.closestPreFinger(id)
      nodeRef ! SearchMessageId(reqNode, id, hopsNum + 1)
    }
    else {
      reqNode ! SearchedMessageId(id, this.successorId, hopsNum)
    }
  }

  def searchedMessageId(id: BigInt, successorId: BigInt, hopsNum: Int) = {
    println(this.ipAddress + " used " + hopsNum + s" hops to search successorId $successorId for messageId " + id)
    this.averagehops = (this.averagehops * sentMessages + hopsNum) / (sentMessages + 1)
    this.sentMessages += 1
    if (this.sentMessages == requestsNum) {
      log.info("ends")
      context.system.actorSelection("/user/Manager") ! NodeEnd(self, this.ipAddress, this.identifier, this.averagehops)
    }
  }

  def printFingerTable() = {
    log.info(this.ipAddress + s" has predecessorId $predecessorId and successorId $successorId")
    for (i <- hashManager.consistentM to (1, -1)) {
      log.info(s"finger $i has node " + this.fingerTable(i).nodeId + " and start " + this.fingerTable(i).start)
    }
  }

  def receive = {
    case FindSuccessor(reqNode: ActorRef, id: BigInt, hopsNum: Int) =>
     this.findSuccessor(reqNode, id, hopsNum)
    case FoundSuccessor(id: BigInt, successorId: BigInt, hopsNum: Int) =>
     this.foundSuccessor(id, successorId, hopsNum)
    case FindUpdateTrail(reqNode: ActorRef, reqNodeId: BigInt, index: Int, hopsNum: Int) =>
      this.findUpdateTrail(reqNode, reqNodeId, index, hopsNum)
    case UpdateFingerTable(nodeRef: ActorRef, nodeId: BigInt, index: Int) =>
      this.updateFingerTable(nodeRef, nodeId, index)
    case FindFinger(reqNode: ActorRef, helpNode: ActorRef, index: Int, id: BigInt, hopsNum: Int) =>
      this.findFinger(reqNode, helpNode, index, id, hopsNum)
    case FoundFinger(helpNode: ActorRef, index: Int, nodeRef: ActorRef, nodeId: BigInt, hopsNum: Int) =>
      this.foundFinger(helpNode, index, nodeRef, nodeId, hopsNum)
    case Join(helpNode: ActorRef) =>
      this.join(helpNode)
    case GetPredecessor(reqNode: ActorRef) =>
      this.getPredecessor(reqNode)
    case SetPredecessor(predecessorId: BigInt, predecessorRef: ActorRef) =>
      this.setPredecessor(predecessorId, predecessorRef)
    case SendMessage(message: String, requestsNum: Int) =>
      this.sendMessage(message, requestsNum)
    case SearchMessageId(reqNode: ActorRef, id: BigInt, hopsNum: Int) =>
      this.searchMessageId(reqNode, id, hopsNum)
    case SearchedMessageId(id: BigInt, successorId: BigInt, hopsNum: Int) =>
      this.searchedMessageId(id, successorId, hopsNum)
  }
}

