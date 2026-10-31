import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatestplus.scalacheck._

class ShiftRowsInvShiftRowsIntegration extends Module {
    val io = IO(new Bundle {
        val in = Input(new State)
        val out = Output(new State)
    })
    val shiftRows = Module(new ShiftRows)
    val invShiftRows = Module(new InvShiftRows)

    shiftRows.io.in := io.in
    invShiftRows.io.in := shiftRows.io.out
    io.out := invShiftRows.io.out
}

class ShiftRowsInvShiftRowsIntegrationSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {
  
  "InvShiftRows" should "shift the rows of a state correctly" in {
    test(new ShiftRowsInvShiftRowsIntegration) { dut =>
      val testValue = Array.tabulate(4, 4) { (i, j) => (i * 4 + j).U(8.W) }
      
      for (i <- 0 to 3) {
        for (j <- 0 to 3) {
          dut.io.in.rows(i)(j).poke(testValue(i)(j))
        }
      }
      
      for (i <- 0 to 3) {
        for (j <- 0 to 3) {
          dut.io.out.rows(i)(j).expect(testValue(i)(j))
        }
      }
    }
  }
}







/*
  "InvShiftRows" should "be the inverse of ShiftRows" in {
    test(new InvShiftRows) {inv_dut =>
      test(new ShiftRows) {dut =>
        val testValue = Array.tabulate(4, 4) { (i, j) => (i * 4 + j).U(8.W)}

        for (i <- 0 to 3) {
          for (j <- 0 to 3) {
            dut.io.in.rows(i)(j).poke(testValue(i)(j))
          }
        }

        for (i <- 0 to 3) {
          for (j <- 0 to 3) {
            inv_dut.io.in.rows(i)(j).poke(dut.io.out.rows(i)(j).peek())
          }
        }

        for (i <- 0 to 3) {
          for (j <- 0 to 3) {
            inv_dut.io.out.rows(i)(j).expect(testValue(i)(j))
          }
        }
      }
    }
  }
*/