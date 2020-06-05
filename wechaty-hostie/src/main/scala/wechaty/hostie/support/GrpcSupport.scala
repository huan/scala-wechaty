package wechaty.hostie.support

import java.util.concurrent.TimeUnit

import io.github.wechaty.grpc.PuppetGrpc
import io.github.wechaty.grpc.puppet.Base
import io.github.wechaty.grpc.puppet.Event.EventRequest
import io.grpc.ManagedChannel
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import wechaty.puppet.LoggerSupport

/**
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2020-06-02
  */
trait GrpcSupport {
  self:GrpcEventSupport with ContactRawSupport with LoggerSupport =>
  protected var grpcClient:PuppetGrpc.PuppetBlockingStub = _
  private var eventStream:PuppetGrpc.PuppetStub = _
  private var channel: ManagedChannel = _
  private def initChannel(endpoint:String)={
    this.channel = NettyChannelBuilder
      .forTarget(endpoint)
      .keepAliveTime(20, TimeUnit.SECONDS)
      //      .keepAliveTimeout(2, TimeUnit.SECONDS)
      .keepAliveWithoutCalls(true)
            .idleTimeout(2, TimeUnit.HOURS)
      .enableRetry()
      .usePlaintext().build()
  }

  protected def startGrpc(endpoint:String): Unit = {
    initChannel(endpoint)
    internalStartGrpc()
  }
  private def internalStartGrpc(){
    info("start grpc client ....")
    this.grpcClient = PuppetGrpc.newBlockingStub(channel)
    startStream()

    this.grpcClient.start(Base.StartRequest.newBuilder().build())
    info("start grpc client done")
  }
  protected def stopGrpc(): Unit ={
    //stop stream
    stopStream()

    //stop grpc client
    this.grpcClient.stop(Base.StopRequest.getDefaultInstance)
  }
  private def stopStream(): Unit ={
    try {
      this.eventStream.stop(Base.StopRequest.getDefaultInstance, new StreamObserver[Base.StopResponse] {
        override def onNext(v: Base.StopResponse): Unit = {}

        override def onError(throwable: Throwable): Unit = {}

        override def onCompleted(): Unit = {}
      })
    }catch{
      case e:Throwable =>
        warn("fail to stop stream {}",e.getMessage)
    }
  }
  private def startStream(){
    this.eventStream = PuppetGrpc.newStub(channel)
    val startRequest = EventRequest.newBuilder().build()
    this.eventStream.event(startRequest,this)
  }
  protected def reconnectStream(){
    info("reconnect stream stream...")
    try{
      stopGrpc()
    }catch{
      case e:Throwable =>
        warn("fail to stop grpc {}",e.getMessage)
    }
    internalStartGrpc()
    info("reconnect stream stream done")

  }
}
