package tech.mlsql.ets.tensorflow.servers

import java.io.{DataInputStream, DataOutputStream}
import java.net.Socket
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import org.apache.spark.internal.Logging
import org.apache.spark.{MLSQLSparkUtils, SparkEnv}
import tech.mlsql.common.utils.distribute.socket.server.{Request, Response, SocketServerInExecutor, SocketServerSerDer}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

class TFWorker[T](taskContextRef: AtomicReference[T])
  extends SocketServerInExecutor[T](taskContextRef, "python-socket-server-in-executor")
    with Logging {

  @volatile private var markClose: AtomicBoolean = new AtomicBoolean(false)
  private val connections = new ArrayBuffer[Socket]()
  val client = new TFClient()

  override def close() = {
    // make sure we only close once
    if (markClose.compareAndSet(false, true)) {
      logInfo(s"Shutdown ${host}. This may caused by the task is killed.")
    }
  }

  def isClosed = markClose.get()

  override def handleConnection(socket: Socket): Unit = {
    connections += socket
    socket.setKeepAlive(true)
    val dIn = new DataInputStream(socket.getInputStream)
    val dOut = new DataOutputStream(socket.getOutputStream)

    while (true) {
      client.readRequest(dIn) match {
        case ClusterSpecResponse(workerList, psList) =>
        case ReportToMasterResponse() =>

      }
    }

    def format_throwable(e: Throwable, skipPrefix: Boolean = false) = {
      (e.toString.split("\n") ++ e.getStackTrace.map(f => f.toString)).map(f => f).toSeq.mkString("\n")
    }


    def format_full_exception(buffer: ArrayBuffer[String], e: Exception, skipPrefix: Boolean = true) = {
      var cause = e.asInstanceOf[Throwable]
      buffer += format_throwable(cause, skipPrefix)
      while (cause.getCause != null) {
        cause = cause.getCause
        buffer += "caused by：\n" + format_throwable(cause, skipPrefix)
      }

    }

  }

  override def host: String = {
    if (SparkEnv.get == null) {
      //When SparkEnv.get is null, the program may run in a test
      //So return local address would be ok.
      "127.0.0.1"
    } else {
      MLSQLSparkUtils.rpcEnv().address.host
    }
  }

}

class TFDriver[T](taskContextRef: AtomicReference[T]) extends TFWorker[T](taskContextRef) {
  private val connections = new ArrayBuffer[Socket]()
  override val client = new TFClient()

  val workers = new java.util.concurrent.CopyOnWriteArraySet[WorkerInfo]()

  override def handleConnection(socket: Socket): Unit = {
    connections += socket
    socket.setKeepAlive(true)
    val dIn = new DataInputStream(socket.getInputStream)
    val dOut = new DataOutputStream(socket.getOutputStream)

    while (true) {
      client.readRequest(dIn) match {
        case ReportToMasterRequest(host, port, jobName, taskIndex, isPs) =>
          workers.add(WorkerInfo(host, port, jobName, taskIndex, isPs, false))
          client.sendResponse(dOut, ReportToMasterResponse())
        case ClusterSpecRequest() =>
          client.sendResponse(dOut, ClusterSpecResponse(workers.toList))

        case JobStatusRequest(jobName, taskIndex, done) =>
          val tempWorker = workers.asScala.filter(f => f.jobName == jobName && f.taskIndex == taskIndex).head
          workers.remove(tempWorker)
          workers.add(tempWorker.copy(done = done))
          client.sendResponse(dOut, JobStatusResponse(workers))
      }
    }

    def format_throwable(e: Throwable, skipPrefix: Boolean = false) = {
      (e.toString.split("\n") ++ e.getStackTrace.map(f => f.toString)).map(f => f).toSeq.mkString("\n")
    }


    def format_full_exception(buffer: ArrayBuffer[String], e: Exception, skipPrefix: Boolean = true) = {
      var cause = e.asInstanceOf[Throwable]
      buffer += format_throwable(cause, skipPrefix)
      while (cause.getCause != null) {
        cause = cause.getCause
        buffer += "caused by：\n" + format_throwable(cause, skipPrefix)
      }

    }

  }
}

class TFClient extends SocketServerSerDer[TFSocketRequest, TFSocketResponse]

case class ClusterSpecRequest() extends Request[TFSocketRequest] {
  override def wrap = TFSocketRequest(clusterSpecRequest = this)
}

case class ClusterSpecResponse(workerInfoList: List[WorkerInfo]) extends Response[TFSocketResponse] {
  override def wrap = TFSocketResponse(clusterSpecResponse = this)

  def workers = {
    workerInfoList.filter(f => !f.isPs)
  }

  def ps = {
    workerInfoList.filter(f => f.isPs)
  }

  def workerTasks = {
    workers.filter(f => !f.isPs).map(f => s"/job:worker/task:${f.taskIndex}")
  }

  def psTasks = {
    ps.map(f => s"/job:ps/task:${f.taskIndex}")
  }
}

case class WorkerInfo(host: String, port: Int, jobName: String, taskIndex: Int, isPs: Boolean, done: Boolean)

case class ReportToMasterRequest(host: String, port: Int, jobName: String, taskIndex: Int, isPs: Boolean) extends Request[TFSocketRequest] {
  override def wrap = TFSocketRequest(reportToMasterRequest = this)
}

case class ReportToMasterResponse() extends Response[TFSocketResponse] {
  override def wrap = TFSocketResponse(reportToMasterResponse = this)
}

case class JobStatusRequest(jobName: String, taskIndex: Int, done: Boolean) extends Request[TFSocketRequest] {
  override def wrap = TFSocketRequest(jobStatusRequest = this)
}


case class JobStatusResponse(jobStatus: List[WorkerInfo]) extends Response[TFSocketResponse] {
  override def wrap = TFSocketResponse(jobStatusResponse = this)
}


case class TFSocketResponse(clusterSpecResponse: ClusterSpecResponse = null,
                            reportToMasterResponse: ReportToMasterResponse = null,
                            jobStatusResponse: JobStatusResponse = null
                           ) {
  def unwrap: Response[_] = {
    if (clusterSpecResponse != null) clusterSpecResponse
    else if (reportToMasterResponse != null) reportToMasterResponse
    else if (jobStatusResponse != null) jobStatusResponse

    else null
  }
}

case class TFSocketRequest(clusterSpecRequest: ClusterSpecRequest = null,
                           reportToMasterRequest: ReportToMasterRequest = null,
                           jobStatusRequest: JobStatusRequest = null
                          ) {
  def unwrap: Request[_] = {
    if (clusterSpecRequest != null) clusterSpecRequest
    else if (reportToMasterRequest != null) reportToMasterRequest
    else if (jobStatusRequest != null) jobStatusRequest
    else null
  }
}




