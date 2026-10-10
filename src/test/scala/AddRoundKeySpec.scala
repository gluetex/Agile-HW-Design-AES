import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatestplus.scalacheck._

class AddRoundKeySpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {
  
  def pokeStateAndKey(dut: AddRoundKey, s: Seq[Seq[Int]], k: Seq[Seq[Int]]): Unit =
    for (i <- 0 to 3; j <- 0 to 3) {
      dut.io.in.rows(i)(j).poke(s(i)(j).U)
      dut.io.key.rows(i)(j).poke(k(i)(j).U)
    }

  def expectState(dut: AddRoundKey, s: Seq[Seq[Int]]): Unit =
    for (i <- 0 to 3; j <- 0 to 3) {
      dut.io.out.rows(i)(j).expect(s(i)(j).U)
    }
  
  val stateGen: Gen[Seq[Seq[Int]]] =
    Gen.listOfN(4, Gen.listOfN(4, Gen.choose(0, 255)))
  
  val stateAndKeyGen: Gen[(Seq[Seq[Int]], Seq[Seq[Int]])] =
  for {
    state <- stateGen
    key   <- stateGen
  } yield (state, key)

  "AddRoundKey" should "xor each byte in the state, with the corresponding byte in the key, for all possible 128-bit values" in {
    forAll(stateAndKeyGen, minSuccessful(200)) { case (state_in, key_in) =>
      test(new AddRoundKey) { dut =>
        pokeStateAndKey(dut, state_in, key_in)
        val expectedValueMutable: Array[Array[Int]] = Array.fill(4, 4)(0)
        for (i <- 0 to 3; j <- 0 to 3) {
          expectedValueMutable(i)(j) = state_in(i)(j) ^ key_in(i)(j)
        }
        val expectedValue = expectedValueMutable.map(_.toSeq).toSeq
        expectState(dut, expectedValue)
      }
    }
  }
}