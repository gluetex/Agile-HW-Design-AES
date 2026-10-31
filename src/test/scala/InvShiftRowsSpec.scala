import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatestplus.scalacheck._

class InvShiftRowsSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {
  
  "InvShiftRows" should "shift the rows of a state correctly" in {
    test(new InvShiftRows) { dut =>
      val testValue = Array.tabulate(4, 4) { (i, j) => (i * 4 + j).U(8.W) }
      
      for (i <- 0 to 3) {
        for (j <- 0 to 3) {
          dut.io.in.rows(i)(j).poke(testValue(i)(j))
        }
      }
      
      dut.io.out.rows(0)(0).expect(testValue(0)(0))
      dut.io.out.rows(0)(1).expect(testValue(0)(1))
      dut.io.out.rows(0)(2).expect(testValue(0)(2))
      dut.io.out.rows(0)(3).expect(testValue(0)(3))
      
      dut.io.out.rows(1)(0).expect(testValue(1)(3))
      dut.io.out.rows(1)(1).expect(testValue(1)(0))
      dut.io.out.rows(1)(2).expect(testValue(1)(1))
      dut.io.out.rows(1)(3).expect(testValue(1)(2))

      dut.io.out.rows(2)(0).expect(testValue(2)(2))
      dut.io.out.rows(2)(1).expect(testValue(2)(3))
      dut.io.out.rows(2)(2).expect(testValue(2)(0))
      dut.io.out.rows(2)(3).expect(testValue(2)(1))
                                            
      dut.io.out.rows(3)(0).expect(testValue(3)(1))
      dut.io.out.rows(3)(1).expect(testValue(3)(2))
      dut.io.out.rows(3)(2).expect(testValue(3)(3))
      dut.io.out.rows(3)(3).expect(testValue(3)(0))
    }
  }
}
