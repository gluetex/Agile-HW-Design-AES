import chisel3._

//Undoes MixColumns (FIPS-197 5.3.3)
class InvMixColumns extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val out = Output(new State)
  })

  //Multiply by 2, shift left and xor with 0x1b if the top bit was 1
  def xtime(b: UInt): UInt = (b << 1)(7, 0) ^ Mux(b(7), 0x1b.U(8.W), 0.U(8.W))

  //Multiply each column with this matrix (the inverse of the MixColumns matrix)
  // [14 11 13  9]
  // [ 9 14 11 13]
  // [13  9 14 11]
  // [11 13  9 14]
  for (c <- 0 to 3) {
    //b, 2b, 4b and 8b for every byte b in the column, calculated once
    val s = Seq.tabulate(4)(r => Seq.iterate(io.in.rows(r)(c), 4)(xtime))

    //Byte r times a constant k < 16: xor together the powers of 2 that make up k
    //(9 = 8+1, 11 = 8+2+1, 13 = 8+4+1, 14 = 8+4+2)
    def times(r: Int, k: Int): UInt = (0 to 3).filter(i => ((k >> i) & 1) == 1).map(s(r)).reduce(_ ^ _)

    io.out.rows(0)(c) := times(0, 14) ^ times(1, 11) ^ times(2, 13) ^ times(3, 9)
    io.out.rows(1)(c) := times(0, 9) ^ times(1, 14) ^ times(2, 11) ^ times(3, 13)
    io.out.rows(2)(c) := times(0, 13) ^ times(1, 9) ^ times(2, 14) ^ times(3, 11)
    io.out.rows(3)(c) := times(0, 11) ^ times(1, 13) ^ times(2, 9) ^ times(3, 14)
  }
}
