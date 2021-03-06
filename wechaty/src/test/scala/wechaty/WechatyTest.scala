package wechaty

import org.junit.jupiter.api.{Assertions, Test}
import wechaty.puppet.schemas.Event.EventFriendshipPayload
import wechaty.puppet.schemas.Puppet.PuppetEventName

/**
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2020-06-16
  */
class WechatyTest extends TestBase {
  @Test
  def test_once: Unit ={
    var reach = 0
    var reach2 = 0
    instance.onOnceMessage(f=>{
      reach += 1
    })
    instance.onMessage(f=>{
      reach2 += 1
    })
    mockRoomMessage()

    emitMessagePayloadEvent()
    emitMessagePayloadEvent()
    Assertions.assertEquals(1,reach)
    Assertions.assertEquals(2,reach2)
  }

  @Test
  def test_friendship: Unit ={
    val payload = new EventFriendshipPayload
    payload.friendshipId="fid"
//    mockEvent( EventType.EVENT_TYPE_FRIENDSHIP->payload)

    var reach = false
    instance.onFriendAdd(f=>{
      reach = true
      Assertions.assertEquals(payload.friendshipId,f.id)
    })

  instance.puppet.emit(PuppetEventName.FRIENDSHIP,payload)
//    awaitEventCompletion(10,TimeUnit.SECONDS)
    Assertions.assertTrue(reach)
  }

}
