import chisel3._

class InvSubBytes extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val out = Output(new State)
  })
  //Put every byte through the inverse S-box
  for (i <- 0 to 3) {
    for (j <- 0 to 3) {
      io.out.rows(i)(j) := InvSBox(io.in.rows(i)(j))
    }
  }
}
