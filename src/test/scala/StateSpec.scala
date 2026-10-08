import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatestplus.scalacheck._

class StateTestModule extends Module {
  val io = IO(new Bundle {
    val in = Input(new State)
    val out = Output(new State)
  })
  io.out := io.in
}

class StateSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {
  
  "A state" should "be addressable" in {
    test(new StateTestModule) { dut =>
      for (i <- 0 to 3) {
        for (j <- 0 to 3) {
          dut.io.in.rows(i)(j).poke((i*4+j).U)
        }
      }
      
      for (i <- 0 to 3) {
        for (j <- 0 to 3) {
          dut.io.out.rows(i)(j).expect((i*4+j).U)
        }
      }
    }
  }

}
