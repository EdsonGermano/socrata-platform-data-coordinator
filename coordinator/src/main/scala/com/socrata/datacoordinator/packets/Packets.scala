package com.socrata.datacoordinator.packets

import java.io.Closeable
import scala.concurrent.duration.Duration

/** A sender writes data to a Receiver.  It is assumed to do so quickly;
  * the receiver will consider getting into a state where its transmit buffer
  * has filled up and not drained within its timeout to be an error. */
trait Packets extends Closeable {
  /** Transmits a message and waits for the response. */
  def send(packet: Packet, timeout: Duration = Duration.Inf)
  def receive(timeout: Duration = Duration.Inf): Option[Packet]
}
