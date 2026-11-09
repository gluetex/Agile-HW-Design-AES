import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import AESReference._

class AESOFBSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  //monte carlo takes ~40 s per record so we only run the first one, set AES_FULL_MCT=1 for all
  val mctRecords: Int = if (sys.env.contains("AES_FULL_MCT")) Int.MaxValue else 1

  def setup(dut: AESOFB, key: Seq[Int], iv: BigInt): Unit = {
    dut.io.key.poke(bytesToBigInt(key).U(dut.keySize.W))
    dut.io.iv.poke(iv.U(128.W))
  }

  //feed one block in and wait for the result
  def process(dut: AESOFB, block: BigInt, first: Boolean): BigInt = {
    val nr = AESEncrypt.rounds(dut.keySize)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.data.poke(block.U(128.W))
    dut.io.in.bits.first.poke(first.B)
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
    var cycles = 1
    while (!dut.io.out.valid.peek().litToBoolean) {
      dut.clock.step()
      cycles += 1
      assert(cycles <= nr + 1, "no output after Nr + 1 cycles")
    }
    val result = dut.io.out.bits.peek().litValue
    dut.clock.step()
    result
  }

  //a whole message, first block starts from the iv
  def processMessage(dut: AESOFB, blocks: Seq[BigInt]): Seq[BigInt] =
    blocks.zipWithIndex.map { case (b, i) => process(dut, b, i == 0) }

  def hex(s: String): BigInt = BigInt(s, 16)

  def blocks(hex: String): Seq[BigInt] = hex.grouped(32).map(BigInt(_, 16)).toSeq

  //OFB from the JDK to compare against
  def ofbJdk(key: Seq[Int], iv: Seq[Int], data: Seq[Int]): Seq[Int] = {
    val cipher = Cipher.getInstance("AES/OFB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.map(_.toByte).toArray, "AES"), new IvParameterSpec(iv.map(_.toByte).toArray))
    cipher.doFinal(data.map(_.toByte).toArray).map(_ & 0xff).toSeq
  }

  //a NIST file has an [ENCRYPT] and a [DECRYPT] part, run both
  def runNistFile(dut: AESOFB, file: String): Unit =
    for (section <- Seq("ENCRYPT", "DECRYPT")) {
      for (v <- NistVectors.load(file, section)) {
        setup(dut, v.key, v.iv.get)
        val (in, expected) = if (section == "DECRYPT") (v.ciphertext, v.plaintext) else (v.plaintext, v.ciphertext)
        assert(processMessage(dut, in) == expected, s"$file [$section] COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  //example from NIST SP 800-38A F.4.1, decrypting is just the same in reverse
  val spKey = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
  val spIv = hex("000102030405060708090a0b0c0d0e0f")
  val spPlaintext = "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                    "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
  val spCiphertext = "3b3fd92eb72dad20333449f8e83cfb4a7789508d16918f03f53c52dac54ed825" +
                     "9740051e9c5fecf64344f7a82260edcc304c6528f659c77866a510d9c1d6ae5e"

  behavior of "AESOFB (AES-128)"

  it should "encrypt the NIST SP 800-38A example" in {
    test(new AESOFB(128)) { dut =>
      setup(dut, spKey, spIv)
      assert(processMessage(dut, blocks(spPlaintext)) == blocks(spCiphertext))
    }
  }

  it should "decrypt the NIST SP 800-38A example" in {
    test(new AESOFB(128)) { dut =>
      setup(dut, spKey, spIv)
      assert(processMessage(dut, blocks(spCiphertext)) == blocks(spPlaintext))
    }
  }

  it should "take Nr + 1 cycles per block and take the next block in the same cycle" in {
    test(new AESOFB(128)) { dut =>
      setup(dut, spKey, spIv)
      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      for (((pt, ct), i) <- blocks(spPlaintext).zip(blocks(spCiphertext)).zipWithIndex) {
        //the next block just waits until the output is taken
        dut.io.in.bits.data.poke(pt.U)
        dut.io.in.bits.first.poke((i == 0).B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        for (_ <- 1 to 10) {
          dut.io.in.ready.expect(false.B)
          dut.io.out.valid.expect(false.B)
          dut.clock.step()
        }
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(ct.U)
      }
    }
  }

  it should "hold the output until out.ready" in {
    test(new AESOFB(128)) { dut =>
      setup(dut, spKey, spIv)
      val pt = blocks(spPlaintext)
      val ct = blocks(spCiphertext)
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.data.poke(pt(0).U)
      dut.io.in.bits.first.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.bits.data.poke(pt(1).U)
      dut.io.in.bits.first.poke(false.B)
      dut.clock.step(10)

      //nobody takes the output so it stays and the next block waits
      for (_ <- 0 until 5) {
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(ct(0).U)
        dut.io.in.ready.expect(false.B)
        dut.clock.step()
      }

      //now take it, the waiting block should go in at the same time
      dut.io.out.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(10)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(ct(1).U)
    }
  }

  for (keySize <- Seq(128, 192, 256)) {
    behavior of s"AESOFB (AES-$keySize)"

    //new iv for every message, so the first flag gets tested too
    it should "match the JDK for random keys, IVs and messages" in {
      test(new AESOFB(keySize)) { dut =>
        val messageGen = Gen.choose(1, 8).flatMap(n => bytesGen(n * 16))
        forAll(bytesGen(keySize / 8), bytesGen(16), messageGen, minSuccessful(100)) {
          (key, iv, message) =>
            setup(dut, key, bytesToBigInt(iv))
            val out = processMessage(dut, message.grouped(16).map(bytesToBigInt).toSeq)
            assert(out.flatMap(bigIntToBytes(_, 16)) == ofbJdk(key, iv, message))
        }
      }
    }

    for (name <- nistFiles) {
      it should s"pass the NIST AESAVS OFB $name test vectors" in {
        test(new AESOFB(keySize))(dut => runNistFile(dut, s"OFB$name$keySize.rsp"))
      }
    }
  }

  behavior of "AESOFB (AES-128)"

  //AESAVS monte carlo: 1000 blocks, first the record text, then the iv, then the previous output
  for (section <- Seq("ENCRYPT", "DECRYPT")) {
    it should s"pass the NIST AESAVS OFB Monte Carlo test, $section (first $mctRecords records)" in {
      test(new AESOFB(128)) { dut =>
        for (v <- NistVectors.load("OFBMCT128.rsp", section).take(mctRecords)) {
          val iv = v.iv.get
          setup(dut, v.key, iv)
          val (start, expected) = if (section == "DECRYPT") (v.ciphertext.head, v.plaintext.head) else (v.plaintext.head, v.ciphertext.head)
          val outputs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var input = start
          for (j <- 0 until 1000) {
            outputs += process(dut, input, j == 0)
            input = if (j == 0) iv else outputs(j - 1)
          }
          assert(outputs.last == expected, s"OFBMCT128.rsp [$section] COUNT = ${v.count}")
        }
      }
    }
  }
}
