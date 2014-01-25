package spray.can.websocket.server

/**
 * This pipeline stage simply forwards the events to and receives commands from
 * the given MessageHandler. It is the final stage of the websocket pipeline,
 * and is how the pipeline interacts with user code.
 *
 * @param handler the actor which will receive the incoming Frames
 */
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import spray.can.server.ServerSettings
import spray.can.server.UHttp
import spray.can.websocket.{ ControlEvent, FrameStreamCommand, FrameOutEvent, FrameCommand, FrameInEvent }
import spray.can.websocket.frame.{ FrameStream, Frame, Opcode, CloseFrame, PongFrame, PingFrame }
import spray.io.Pipeline
import spray.io.PipelineContext
import spray.io.Pipelines
import spray.io.RawPipelineStage
import spray.io.SslTlsContext

object WebSocketFrontend {

  def apply(settings: ServerSettings, handler: ActorRef) = new RawPipelineStage[SslTlsContext] {
    def apply(context: SslTlsContext, commandPL: CPL, eventPL: EPL): Pipelines = new Pipelines {
      /**
       * proxy actor between handler and pipelines owner. It behaves as the sender
       * (instead of pipelines owner actor) which is telling handler:
       *
       *   pipelines' owner <-> receiverRef <-> handler
       */
      val receiverRef = context.actorContext.actorOf(Props(new HandlerResponseReceiver(context)))

      val commandPipeline = commandPL

      val eventPipeline: EPL = {

        // we have to use ControlEvent to keep message order by sending them to handler then bounce back
        // TODO we need better solution
        case FrameOutEvent(frame: CloseFrame) => commandPL(Pipeline.Tell(handler, ControlEvent(frame), receiverRef))
        case FrameOutEvent(frame: PongFrame)  => commandPL(Pipeline.Tell(handler, ControlEvent(frame), receiverRef))
        case FrameOutEvent(frame)             => commandPL(FrameCommand(frame))

        case FrameInEvent(frame: CloseFrame)  => commandPL(FrameCommand(frame))
        case FrameInEvent(frame: PongFrame)   => // Server should not receive pong frame, just ignore it
        case FrameInEvent(frame: PingFrame)   => // We'll auto pong it, does not need to tell handler
        case FrameInEvent(frame)              => commandPL(Pipeline.Tell(handler, frame, receiverRef))

        case ev: Tcp.ConnectionClosed         => commandPL(Pipeline.Tell(handler, ev, receiverRef))
        case ev @ UHttp.Upgraded              => commandPL(Pipeline.Tell(handler, ev, receiverRef))
        case ev                               => eventPL(ev)
      }
    }

    /**
     * Receive handler's send and put on context.actorContext.self's pipelines head
     * TODO implement it as UnregisteredActorRef?
     */
    class HandlerResponseReceiver(context: PipelineContext) extends Actor {
      def receive = {
        case x: Frame       => context.actorContext.self ! FrameCommand(x)
        case x: FrameStream => context.actorContext.self ! FrameStreamCommand(x)
        case Tcp.Close      => context.actorContext.self ! Tcp.Close
      }
    }

  }
}