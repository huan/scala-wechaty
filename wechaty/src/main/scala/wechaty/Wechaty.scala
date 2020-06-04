package wechaty

import wechaty.helper.ImplicitHelper._
import wechaty.hostie.PuppetHostie
import wechaty.puppet.events.EventEmitter
import wechaty.puppet.schemas.Events._
import wechaty.puppet.schemas.Puppet.PuppetOptions
import wechaty.puppet.LoggerSupport
import wechaty.user.{Contact, Message}

import scala.language.implicitConversions


/**
  **
  * Main bot class.
  *
  * A `Bot` is a WeChat client depends on which puppet you use.
  *
  * See more:
  * - [What is a Puppet in Wechaty](https://github.com/wechaty/wechaty-getting-started/wiki/FAQ-EN#31-what-is-a-puppet-in-wechaty)
  *
  * > If you want to know how to send message, see [Message](#Message) <br>
  * > If you want to know how to get contact, see [Contact](#Contact)
  *
  * @example <caption>The World's Shortest ChatBot Code: 6 lines of Scala</caption>
  * val options = new WechaytOptions
  * val bot = Wechaty.instance(options)
  * bot.onScan(payload => println("['https://api.qrserver.com/v1/create-qr-code/?data=',encodeURIComponent(qrcode),'&size=220x220&margin=20',].join('')"))
  * bot.onLogin(user => println("User ${user} logged in"))
  * bot.onMessage(message => println("Message: ${message}"))
  * bot.start()
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2020-06-01
  */
object Wechaty {
  private var globalInstance:Wechaty = _
  def instance(options: WechatyOptions): Wechaty = {
    if (options != null && globalInstance != null) throw new Error("instance can be only initialized once by options !")
    if (globalInstance == null) globalInstance = new Wechaty(options)
    globalInstance
  }
}

class WechatyOptions {
  var name: String = "Wechaty"
  var puppet: String = "wechaty-puppet-hostie"
  var puppetOptions: Option[PuppetOptions] = None
  var ioToken: Option[String] = None
}

class Wechaty(private val options: WechatyOptions) extends LoggerSupport{
  private implicit var hostie:PuppetHostie = _

  def onScan(listener: EventScanPayload=>Unit): Wechaty = {
    EventEmitter.addListener(PuppetEventName.SCAN,listener)
    this
  }
  def onLogin(listener:Contact =>Unit):Wechaty={
    EventEmitter.addListener[EventLoginPayload](PuppetEventName.LOGIN,listener)
    this
  }
  def onMessage(listener:Message =>Unit):Wechaty={
    EventEmitter.addListener[EventMessagePayload](PuppetEventName.MESSAGE,listener)
    this
  }
  def onLogout(listener:Contact=>Unit):Wechaty={
    EventEmitter.addListener[EventLogoutPayload](PuppetEventName.LOGOUT,listener)
    this
  }

  def start(): Unit = {
    val option = options.puppetOptions match{
      case Some(o) => o
      case _ => new PuppetOptions
    }
    this.hostie = new PuppetHostie(option)
    this.hostie.start()
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      override def run(): Unit ={
        info("stop puppet...")
        Wechaty.this.hostie.stop()
      }
    }))

  }
}


