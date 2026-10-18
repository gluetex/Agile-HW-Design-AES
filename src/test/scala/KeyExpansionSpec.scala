import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import AESReference._

class KeyExpansionSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  def pokeKey(dut: KeyExpansion, key: Seq[Int]): Unit =
    dut.io.key.poke(bytesToBigInt(key).U(dut.keySize.W))

  def expectRoundKey(dut: KeyExpansion, k: Int, expected: Seq[Seq[Int]]): Unit =
    for (r <- 0 to 3; c <- 0 to 3) {
      dut.io.roundKeys(k).rows(r)(c).expect(expected(r)(c).U, s"round key $k, row $r, column $c")
    }

  //Compare all round keys with the software model
  def expectAllRoundKeys(dut: KeyExpansion, key: Seq[Int]): Unit = {
    pokeKey(dut, key)
    val expected = expandKey(key)
    for (k <- 0 to dut.nr) expectRoundKey(dut, k, expected(k))
  }

  //Words of the key schedule as a round key, w(c) is the 4 bytes of column c
  def roundKey(words: String*): Seq[Seq[Int]] = {
    val w = words.map(hexToBytes)
    Seq.tabulate(4, 4)((r, c) => w(c)(r))
  }

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  val keys = Map(
    128 -> "2b7e151628aed2a6abf7158809cf4f3c",                                 //FIPS-197 A.1
    192 -> "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",                 //FIPS-197 A.2
    256 -> "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4") //FIPS-197 A.3

  behavior of "KeyExpansion (AES-128)"

  it should "give the round keys of the FIPS-197 Appendix A.1 example" in {
    test(new KeyExpansion(128)) { dut =>
      pokeKey(dut, hexToBytes(keys(128)))
      //round key 0 is the cipher key
      expectRoundKey(dut, 0, roundKey("2b7e1516", "28aed2a6", "abf71588", "09cf4f3c"))
      expectRoundKey(dut, 1, roundKey("a0fafe17", "88542cb1", "23a33939", "2a6c7605"))
      expectRoundKey(dut, 10, roundKey("d014f9a8", "c9ee2589", "e13f0cc8", "b6630ca6"))
    }
  }

  it should "give the round keys of the FIPS-197 Appendix C.1 example" in {
    test(new KeyExpansion(128)) { dut =>
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      expectRoundKey(dut, 0, roundKey("00010203", "04050607", "08090a0b", "0c0d0e0f"))
      expectRoundKey(dut, 1, roundKey("d6aa74fd", "d2af72fa", "daa678f1", "d6ab76fe"))
      expectRoundKey(dut, 10, roundKey("13111d7f", "e3944a17", "f307a78b", "4d2b30c5"))
    }
  }

  it should "handle the all zero and the all one key" in {
    test(new KeyExpansion(128)) { dut =>
      expectAllRoundKeys(dut, Seq.fill(16)(0x00))
      expectAllRoundKeys(dut, Seq.fill(16)(0xff))
    }
  }

  it should "match the software model for random keys" in {
    test(new KeyExpansion(128)) { dut =>
      forAll(bytesGen(16), minSuccessful(200)) { key => expectAllRoundKeys(dut, key) }
    }
  }

  it should "match the software model for the keys of the NIST AESAVS ECB test vectors" in {
    test(new KeyExpansion(128)) { dut =>
      for (file <- Seq("GFSbox", "KeySbox", "VarKey", "MMT"); v <- NistVectors.load(s"ECB${file}128.rsp")) {
        expectAllRoundKeys(dut, v.key)
      }
    }
  }

  it should "change the round keys when the cipher key changes" in {
    test(new KeyExpansion(128)) { dut =>
      pokeKey(dut, hexToBytes(keys(128)))
      expectRoundKey(dut, 10, roundKey("d014f9a8", "c9ee2589", "e13f0cc8", "b6630ca6"))
      pokeKey(dut, hexToBytes("000102030405060708090a0b0c0d0e0f"))
      expectRoundKey(dut, 10, roundKey("13111d7f", "e3944a17", "f307a78b", "4d2b30c5"))
    }
  }

  //The same generator also gives the key schedule of AES-192 and AES-256
  for ((keySize, lastRoundKey) <- Seq(
    (192, roundKey("e98ba06f", "448c773c", "8ecc7204", "01002202")),
    (256, roundKey("fe4890d1", "e6188d0b", "046df344", "706c631e")))) {

    behavior of s"KeyExpansion (AES-$keySize)"

    it should s"give the last round key of the FIPS-197 Appendix A example" in {
      test(new KeyExpansion(keySize)) { dut =>
        pokeKey(dut, hexToBytes(keys(keySize)))
        expectRoundKey(dut, dut.nr, lastRoundKey)
      }
    }

    it should "handle the all zero and the all one key" in {
      test(new KeyExpansion(keySize)) { dut =>
        expectAllRoundKeys(dut, Seq.fill(keySize / 8)(0x00))
        expectAllRoundKeys(dut, Seq.fill(keySize / 8)(0xff))
      }
    }

    it should "match the software model for random keys" in {
      test(new KeyExpansion(keySize)) { dut =>
        forAll(bytesGen(keySize / 8), minSuccessful(200)) { key => expectAllRoundKeys(dut, key) }
      }
    }

    it should "match the software model for the keys of the NIST AESAVS ECB test vectors" in {
      test(new KeyExpansion(keySize)) { dut =>
        for (file <- Seq("GFSbox", "KeySbox", "VarKey", "MMT"); v <- NistVectors.load(s"ECB$file$keySize.rsp")) {
          expectAllRoundKeys(dut, v.key)
        }
      }
    }
  }

  behavior of "KeyExpansion"

  it should "refuse key sizes other than 128, 192 and 256" in {
    assertThrows[IllegalArgumentException] {
      test(new KeyExpansion(160)) { _ => }
    }
  }
}
