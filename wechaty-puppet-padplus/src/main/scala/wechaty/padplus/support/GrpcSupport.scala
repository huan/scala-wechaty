package wechaty.padplus.support

import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.{CannedAccessControlList, GeneratePresignedUrlRequest, ObjectMetadata, PutObjectRequest}
import com.fasterxml.jackson.databind.JsonNode
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.typesafe.scalalogging.LazyLogging
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import wechaty.padplus.PuppetPadplus
import wechaty.padplus.grpc.PadPlusServerGrpc
import wechaty.padplus.grpc.PadPlusServerOuterClass._
import wechaty.puppet.schemas.Puppet

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import scala.util.{Failure, Success, Try}

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
  protected implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  type CallbackType = StreamResponse => Unit
  protected lazy val callbackPool                           = {
    Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(1,TimeUnit.MINUTES).build().asInstanceOf[Cache[String,CallbackType]]
  }


  protected def startGrpc(endpoint: String): Unit = {
    initChannel(endpoint)
    internalStartGrpc()
    //from https://github.com/wechaty/java-wechaty/blob/master/wechaty-puppet/src/main/kotlin/Puppet.kt
    executorService.scheduleAtFixedRate(() => {
      val seq = HEARTBEAT_COUNTER.incrementAndGet()
      try {
        asyncRequest(ApiType.HEARTBEAT)
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
//    startStream()
    logger.info("start grpc client done")
  }

  private[wechaty] def startStream() {
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
  protected def syncRequest[T:TypeTag](apiType: ApiType,data:Option[Any]=None)(implicit classTag: ClassTag[T]): T ={
    val future= asyncRequest[T](apiType,data)
    Await.result(future, 10 seconds)
  }
  protected def generateTraceId(apiType:ApiType): String={
    UUID.randomUUID().toString
  }
  protected def asyncRequest[T : TypeTag ](apiType: ApiType,data:Option[Any]=None)(implicit classTag: ClassTag[T]): Future[T] ={
    val request = RequestObject.newBuilder()
    request.setToken(option.token.get)
    uinOpt match{
      case Some(id) =>
        request.setUin(id)
      case _ =>
    }
    request.setApiType(apiType)
    data match{
      case Some(str:String) =>
        request.setParams(str)
      case Some(d) =>
        request.setParams(Puppet.objectMapper.writeValueAsString(d))
      case _ =>
    }
//    val requestId = UUID.randomUUID().toString
//    request.setRequestId(requestId)
    val traceId= generateTraceId(apiType)
    request.setTraceId(traceId)
    logger.debug("request:{}",request.build())
    val p = Promise[T]()

    val callbackDelegate:CallbackType=(streamResponse:StreamResponse)=>{
      if(p.isCompleted){
        logger.warn("promise is completed ,{}",p)
      }else {
        p.complete(Try {
          typeOf[T] match{
            case t if t =:= typeOf[Nothing] =>
              null.asInstanceOf[T]
            case t if t =:= typeOf[JsonNode] =>
              Puppet.objectMapper.readTree(streamResponse.getData).asInstanceOf[T]
            case _ =>
              try {
                Puppet.objectMapper.readValue(streamResponse.getData, classTag.runtimeClass).asInstanceOf[T]
              }catch{
                case e:Throwable =>
                  logger.error(e.getMessage,e)
                  throw e
              }
          }
        })
      }
    }
    //过滤不需要返回
    typeOf[T] match{
      case t if t =:= typeOf[Nothing] =>
      case _ => callbackPool.put(traceId,callbackDelegate)
    }
    val response = grpcClient.request(request.build())
    logger.debug(s"request $apiType response $response")

    if(response.getResult != "success"){
      //fail?
      logger.error("fail to request grpc,response {}",response)
      p.failure(new IllegalAccessException("fail to request ,grpc result:"+response))
    }

    p.future
  }
    private val ACCESS_KEY_ID = "AKIA3PQY2OQG5FEXWMH6"
    private val BUCKET= "macpro-message-file"
    private val EXPIRE_TIME= 3600 * 24 * 3
    private val PATH= "image-message"
    private val SECRET_ACCESS_KEY= "jw7Deo+W8l4FTOL2BXd/VubTJjt1mhm55sRhnsEn"
//  private val s3 = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY));
    private val s3=AmazonS3ClientBuilder.standard()
    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(ACCESS_KEY_ID, SECRET_ACCESS_KEY)))
  .enablePayloadSigning()
      .withRegion(Regions.CN_NORTHWEST_1).build(); // 此处根据自己的 s3 地区位置改变

  def uploadFile (filename: String, stream: InputStream) {
//    ACL: "public-read",
//    const s3 = new AWS.S3({ region: "cn-northwest-1", signatureVersion: "v4" })
    val meta = new ObjectMetadata
    val key=PATH+"/"+filename
    val params = new PutObjectRequest(BUCKET,key,stream,meta)
    val result = s3.putObject(params.withCannedAcl(CannedAccessControlList.PublicRead));
    //获取一个request
    val urlRequest = new GeneratePresignedUrlRequest( BUCKET, key);
    //生成公用的url
    s3.generatePresignedUrl(urlRequest);
  }
}
