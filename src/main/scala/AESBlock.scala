import chisel3._
import chisel3.util._

//Convert between a 128-bit block and a State (FIPS-197 3.4)
//Byte n of the block (n = 0 is the most significant byte, like in the NIST hex strings)
//goes to row n % 4, column n / 4. So the block is filled column by column.
object AESBlock {
  def toState(block: UInt): State = {
    val s = Wire(new State)
    for (r <- 0 to 3; c <- 0 to 3) {
      val n = 4 * c + r
      s.rows(r)(c) := block(127 - 8 * n, 120 - 8 * n)
    }
    s
  }

  //Cat puts the first element at the top, so byte 0 ends up as the MSB
  def fromState(s: State): UInt =
    Cat(for (c <- 0 to 3; r <- 0 to 3) yield s.rows(r)(c))
}
