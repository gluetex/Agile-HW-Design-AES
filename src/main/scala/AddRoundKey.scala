import chisel3._

class AddRoundKey extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val key = Input(new State)
    val out = Output(new State)
  })

  for (i <- 0 to 3) {
    for (j <- 0 to 3) {
      io.out.rows(i)(j) := io.in.rows(i)(j) ^ io.key.rows(i)(j)
    }
  }
}