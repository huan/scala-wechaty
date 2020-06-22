package wechaty.padplus.support

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap, Executors, TimeUnit}

import com.typesafe.scalalogging.LazyLogging
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import wechaty.padplus.PuppetPadplus
import wechaty.padplus.grpc.PadPlusServerGrpc
import wechaty.padplus.grpc.PadPlusServerOuterClass.{ApiType, InitConfig, RequestObject, ResponseObject, StreamResponse}
import wechaty.puppet.schemas.Puppet

/**
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2020-06-21
  */
trait GrpcSupport {
  self: PuppetPadplus with LazyLogging =>
  private val executorService = Executors.newSingleThreadScheduledExecutor()
  //from https://github.com/wechaty/java-wechaty/blob/master/wechaty-puppet/src/main/kotlin/Puppet.kt
  private val HEARTBEAT_COUNTER = new AtomicLong()
  private val HOSTIE_KEEPALIVE_TIMEOUT = 15 * 1000L
  private val DEFAULT_WATCHDOG_TIMEOUT = 60L
  protected var grpcClient: PadPlusServerGrpc.PadPlusServerBlockingStub= _
  private var eventStream: PadPlusServerGrpc.PadPlusServerStub = _
  protected var channel: ManagedChannel = _
  protected val callbackPool = new ConcurrentHashMap[String,StreamResponse=>Unit]()

  protected def startGrpc(endpoint: String): Unit = {
    initChannel(endpoint)
    internalStartGrpc()
    //from https://github.com/wechaty/java-wechaty/blob/master/wechaty-puppet/src/main/kotlin/Puppet.kt
    executorService.scheduleAtFixedRate(() => {
      val seq = HEARTBEAT_COUNTER.incrementAndGet()
      try {
        request(ApiType.HEARTBEAT)
      } catch {
        case e: Throwable =>
          logger.warn("ding exception:{}", e.getMessage)
        //ignore any exception
      }
    }, HOSTIE_KEEPALIVE_TIMEOUT, HOSTIE_KEEPALIVE_TIMEOUT, TimeUnit.MILLISECONDS)
  }
  protected def initChannel(endpoint: String) = {
    option.channelOpt match {
      case Some(channel) =>
        this.channel = channel
      case _ =>
        /*
    this.channel = NettyChannelBuilder
      .forTarget(endpoint)
      .keepAliveTime(20, TimeUnit.SECONDS)
      //      .keepAliveTimeout(2, TimeUnit.SECONDS)
      .keepAliveWithoutCalls(true)
            .idleTimeout(2, TimeUnit.HOURS)
      .enableRetry()
      .usePlaintext().build()
      */
        this.channel = ManagedChannelBuilder.forTarget(endpoint)
          .maxInboundMessageSize(1024 * 1024 * 150)
          .usePlaintext().build()
    }
  }

  protected def reconnectStream() {
    logger.info("reconnect stream stream...")
    try {
      stopGrpc()
    } catch {
      case e: Throwable =>
        logger.warn("fail to stop grpc {}", e.getMessage)
    }
    internalStartGrpc()
    logger.info("reconnect stream stream done")

  }

  private def internalStartGrpc() {
    logger.info("start grpc client ....")
    this.grpcClient = PadPlusServerGrpc.newBlockingStub(channel)
    startStream()

//    this.grpcClient.start(Base.StartRequest.newBuilder().build())
//
//    try{
//      //sometime the grpc can't work well,so logout before start bot
//      //      this.grpcClient.logout(Base.LogoutRequest.newBuilder().build())
//    }catch{
//      case e:Throwable=>
//        logger.warn(e.getMessage)
//    }
    logger.info("start grpc client done")
  }

  private def startStream() {
    this.eventStream = PadPlusServerGrpc.newStub(channel)
    val initConfig = InitConfig.newBuilder().setToken(option.token.get).build()
    this.eventStream.init(initConfig, this)
  }

  protected def stopGrpc(): Unit = {
    if(option.channelOpt.isEmpty) {  //if no test!
      //stop stream
      stopStream()

      //stop grpc client
      this.grpcClient.request(RequestObject.newBuilder().setApiType(ApiType.CLOSE).setToken(option.token.get).build())
      this.channel.shutdownNow()
    }
  }

  private def stopStream(): Unit = {
    //do nothing
  }
  protected def request(apiType: ApiType,data:Option[Any]=None): ResponseObject ={
    val request = RequestObject.newBuilder()
    request.setToken(option.token.get)
    selfIdOpt() match{
      case Some(id) =>
        request.setUin(id)
      case _ =>
    }
    request.setApiType(apiType)
    data match{
      case Some(d) =>
        request.setParams(Puppet.objectMapper.writeValueAsString(d))
      case _ =>
    }
    val requestId = UUID.randomUUID().toString
    request.setRequestId(requestId)
    val traceId= UUID.randomUUID().toString
    request.setTraceId(traceId)
    val response = grpcClient.request(request.build())
    logger.debug("request->response:{}",response)
    response
  }
}