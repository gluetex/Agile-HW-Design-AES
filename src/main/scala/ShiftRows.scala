import chisel3._

class ShiftRows extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val out = Output(new State)
  })
  // No shifting for the first row
  io.out.rows(0) := io.in.rows(0)

  // Shift row 1 by 1
  io.out.rows(1)(0) := io.in.rows(1)(1)
  io.out.rows(1)(1) := io.in.rows(1)(2)
  io.out.rows(1)(2) := io.in.rows(1)(3)
  io.out.rows(1)(3) := io.in.rows(1)(0)

  // Shift row 2 by 2
  io.out.rows(2)(0) := io.in.rows(2)(2)
  io.out.rows(2)(1) := io.in.rows(2)(3)
  io.out.rows(2)(2) := io.in.rows(2)(0)
  io.out.rows(2)(3) := io.in.rows(2)(1)

  // Shift row 3 by 3
  io.out.rows(3)(0) := io.in.rows(3)(3)
  io.out.rows(3)(1) := io.in.rows(3)(0)
  io.out.rows(3)(2) := io.in.rows(3)(1)
  io.out.rows(3)(3) := io.in.rows(3)(2)
}
