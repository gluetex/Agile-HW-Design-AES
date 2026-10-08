import chisel3._

class State extends Bundle {
  val rows = Vec(4, Vec(4, UInt(8.W)))
}
