import chisel3._

class MixColumns extends Module {
    val io = IO(new Bundle {
        val in = Input(new State)
        val out = Output(new State)
    })

    //Multiply by 2, shift left and xor with 0x1b if the top bit was 1
    def xtime(b: UInt): UInt = (b << 1)(7, 0) ^ Mux(b(7), 0x1b.U(8.W), 0.U(8.W))

    //Multiply by 3 (3*b = 2*b xor b)
    def times3(b: UInt): UInt = xtime(b) ^ b

    //Multiply each column with this matrix
    // [2 3 1 1]
    // [1 2 3 1]
    // [1 1 2 3]
    // [3 1 1 2]
    for (c <- 0 to 3) {
        val s0 = io.in.rows(0)(c)
        val s1 = io.in.rows(1)(c)
        val s2 = io.in.rows(2)(c)
        val s3 = io.in.rows(3)(c)

        io.out.rows(0)(c) := xtime(s0) ^ times3(s1) ^ s2 ^ s3
        io.out.rows(1)(c) := s0 ^ xtime(s1) ^ times3(s2) ^ s3
        io.out.rows(2)(c) := s0 ^ s1 ^ xtime(s2) ^ times3(s3)
        io.out.rows(3)(c) := times3(s0) ^ s1 ^ s2 ^ xtime(s3)
    }
}
