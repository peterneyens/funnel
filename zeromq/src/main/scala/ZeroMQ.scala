package oncue.svc.funnel
package zeromq

import org.zeromq.ZMQ, ZMQ.Context, ZMQ.Socket
import scalaz.concurrent.Task
import scalaz.stream.{Process,Channel,io}
import scalaz.stream.async.mutable.Signal
import journal.Logger
import java.net.URI
import scalaz.\/

/**
 * Model 0mq as a set of streams. Primary API should be the `link` function.
 * Example usage:
 *
 * ```
 * Ø.link(E)(K)(Ø.receive).map(new String(_)).to(io.stdOut)
 * ```
 */
object ZeroMQ {
  private[zeromq] val log = Logger[ZeroMQ.type]

  /**
   * A simple pair of Context and Socket. This makes referencing these two things
   * just a bit nicer when it comes to doing resource deallocation upon
   * stream completation.
   */
  case class Connection(
    socket: Socket,
    context: Context
  )

  /////////////////////////////// PRIMITIVES ///////////////////////////////////

  /**
   * Use this to see if the 0mq module can actually be used. This is useful to verify
   * that the OS has the correct native dependencies installed properly.
   */
  def isEnabled: Boolean =
    \/.fromTryCatchThrowable[String, UnsatisfiedLinkError](ZMQ.getVersionString).isRight

  /**
   * Create a `Process` that automatically does setup and tear-down for a 0mq socket
   * when the stream `k` emits `false`. All the while `k` is `true` the 0mq stream
   * will be active and executing the function `f` on the specified socket (in practice
   * this is using the `receive` or `write` functions).
   */
  def linkP[O](e: Endpoint
    )(k: Process[Task,Boolean]
    )(f: Socket => Process[Task,O]): Process[Task, O] =
    resource(setup(e))(r => destroy(r, e)){ connection =>
      haltWhen(k){
        f(connection.socket)
      }
    }

  /**
   * Rather than using a manually defined `Process` as the kill stream, extract a stream
   * from a `Signal`.
   */
  def link[O](e: Endpoint
    )(k: Signal[Boolean]
    )(f: Socket => Process[Task,O]): Process[Task, O] =
    linkP(e)(k.continuous)(f)

  /**
   * Given a `org.zeromq.ZMQ.Socket` pull bytes off of the wire. This particular function
   * assumes that there is a message framing system like this:
   *
   * +---------------+
   * |     Header    |
   * +---------------+
   * |      Body     |
   * +---------------+
   *
   * If another framing stratagy is needed, another can easily be created by user-level
   * engineers. Simply make another `Socket => Process[Task, Transported]`.
   */
  def receive(socket: Socket): Process[Task, Transported] = {
    Process.eval(Task.delay {
      val header: Array[Byte] = socket.recv
      val body: Array[Byte]   = socket.recv
      Transported(new String(header), body)
    }) ++ receive(socket)
  }

  /**
   * Similar to `receive` but pushing messages to the socket. A `Channel` is essentially
   * an effectfull stream, that when given a `Array[Byte]` will write that payload to the
   * 0mq socket. The idea here is that any messages one wishes to send are converted to
   * `Array[Byte]` using `map` or similar on the input stream; this makes the 0mq streams
   * not care about serialisation at all.
   */
  def write(socket: Socket): Channel[Task, Array[Byte], Boolean] =
    io.channel(bytes =>
      Task.delay {
        // log.debug(s"Sending ${bytes.length}")
        socket.sendMore("FMS/1")
        val bool = socket.send(bytes, 0) // the zero here is "flags"
        bool
      }
    )

  /////////////////////////////// INTERNALS ///////////////////////////////////

  /**
   *
   */
  private[zeromq] def haltWhen[O](
    kill: Process[Task,Boolean])(
    input: Process[Task,O]
  ): Process[Task,O] =
    kill.zip(input).takeWhile(_._1).map(_._2)

  /**
   *
   */
  private[zeromq] def resource[F[_],R,O](
    acquire: F[R])(
    release: R => F[Unit])(
    proc: R => Process[F,O]
  ): Process[F,O] =
    Process.eval(acquire).flatMap { r =>
      proc(r).onComplete(Process.eval_(release(r)))
    }

  /**
   *
   */
  private[zeromq] def setup(
    endpoint: Endpoint,
    threadCount: Int = 1
  ): Task[Connection] = {
    log.info(s"Setting up endpoint '${endpoint.location}'...")
    for {
      a <- Task.delay(ZMQ.context(threadCount))
      b <- endpoint.configure(a)
    } yield Connection(b,a)
  }

  /**
   * This is truly a totally unmaintainable function, and as such, it needs
   * some documentation to explain what the fuck is going on here.
   *
   * As it turns out, ZeroMQ takes a dump if you try to close the socket context on
   * the same thread from which you terminated the socket itself. So, given
   * that all Tasks in this module use `delay` to delegate thread control to the
   * caller (and subsequently have that Task blocked by whomever is running),
   * we just start an inner Task that we use to dispatch to another thread
   * simply to close the socket.
   *
   * This is ugly, and as far as I can tell, it works.
   */
  private[zeromq] def destroy(c: Connection, e: Endpoint): Task[Unit] =
    Task.delay {
      log.info(s"Destroying connection for endpoint: $e")
      try {
        log.warn(s"Trying to close socket in thread '${Thread.currentThread.getName}'")
        c.socket.close()
        Task {
          log.warn(s"Trying to close context in thread '${Thread.currentThread.getName}'")
          c.context.close()
        }.runAsync(e => log.warn(s"Result of closing context was: $e"))
      } catch {
        case e: java.nio.channels.ClosedChannelException => ()
      }
    }
}