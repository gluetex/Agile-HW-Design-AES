import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalacheck.{Gen, Shrink}
import org.scalatestplus.scalacheck._
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import AESReference._

class AESCBCSpec extends AnyFlatSpec
  with ChiselScalatestTester
  with ScalaCheckPropertyChecks {

  //The full Monte Carlo test is 100 x 1000 blocks, one record takes ~40 s,
  //so by default only the first one is run. Set AES_FULL_MCT=1 to run all of them.
  val mctRecords: Int = if (sys.env.contains("AES_FULL_MCT")) Int.MaxValue else 1

  def setup(dut: AESCBC, key: Seq[Int], iv: BigInt, decrypt: Boolean): Unit = {
    dut.io.key.poke(bytesToBigInt(key).U(dut.keySize.W))
    dut.io.iv.poke(iv.U(128.W))
    dut.io.decrypt.poke(decrypt.B)
  }

  //Send one block in, wait for the result and take it
  def process(dut: AESCBC, block: BigInt, first: Boolean): BigInt = {
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

  //A whole message, the first block starts from the IV
  def processMessage(dut: AESCBC, blocks: Seq[BigInt]): Seq[BigInt] =
    blocks.zipWithIndex.map { case (b, i) => process(dut, b, i == 0) }

  def hex(s: String): BigInt = BigInt(s, 16)

  def blocks(hex: String): Seq[BigInt] = hex.grouped(32).map(BigInt(_, 16)).toSeq

  //Independent reference: the CBC mode in the JDK
  def cbcJdk(key: Seq[Int], iv: Seq[Int], data: Seq[Int], decrypt: Boolean): Seq[Int] = {
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    val mode = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
    cipher.init(mode, new SecretKeySpec(key.map(_.toByte).toArray, "AES"), new IvParameterSpec(iv.map(_.toByte).toArray))
    cipher.doFinal(data.map(_.toByte).toArray).map(_ & 0xff).toSeq
  }

  //Both parts of a NIST file: [ENCRYPT] plaintext -> ciphertext, [DECRYPT] ciphertext -> plaintext
  def runNistFile(dut: AESCBC, file: String): Unit =
    for (decrypt <- Seq(false, true)) {
      val section = if (decrypt) "DECRYPT" else "ENCRYPT"
      for (v <- NistVectors.load(file, section)) {
        setup(dut, v.key, v.iv.get, decrypt)
        val (in, expected) = if (decrypt) (v.ciphertext, v.plaintext) else (v.plaintext, v.ciphertext)
        assert(processMessage(dut, in) == expected, s"$file [$section] COUNT = ${v.count}")
      }
    }

  val nistFiles = Seq("GFSbox", "KeySbox", "VarTxt", "VarKey", "MMT")

  val bytesGen: Int => Gen[Seq[Int]] = n => Gen.listOfN(n, Gen.choose(0, 255))
  implicit val noShrink: Shrink[Seq[Int]] = Shrink.shrinkAny

  //NIST SP 800-38A F.2.1 (encrypt) and F.2.2 (decrypt), CBC-AES128
  val spKey = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
  val spIv = hex("000102030405060708090a0b0c0d0e0f")
  val spPlaintext = "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                    "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
  val spCiphertext = "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2" +
                     "73bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7"

  behavior of "AESCBC (AES-128)"

  it should "encrypt the NIST SP 800-38A example" in {
    test(new AESCBC(128)) { dut =>
      setup(dut, spKey, spIv, decrypt = false)
      assert(processMessage(dut, blocks(spPlaintext)) == blocks(spCiphertext))
    }
  }

  it should "decrypt the NIST SP 800-38A example" in {
    test(new AESCBC(128)) { dut =>
      setup(dut, spKey, spIv, decrypt = true)
      assert(processMessage(dut, blocks(spCiphertext)) == blocks(spPlaintext))
    }
  }

  for (decrypt <- Seq(false, true)) {
    val (from, to) = if (decrypt) (spCiphertext, spPlaintext) else (spPlaintext, spCiphertext)
    val direction = if (decrypt) "decrypting" else "encrypting"

    it should s"take Nr + 1 cycles per block and take the next block in the same cycle when $direction" in {
      test(new AESCBC(128)) { dut =>
        setup(dut, spKey, spIv, decrypt)
        dut.io.out.ready.poke(true.B)
        dut.io.in.valid.poke(true.B)
        for (((in, out), i) <- blocks(from).zip(blocks(to)).zipWithIndex) {
          //the next block is waiting the whole time, it is only taken when the output is taken
          dut.io.in.bits.data.poke(in.U)
          dut.io.in.bits.first.poke((i == 0).B)
          dut.io.in.ready.expect(true.B)
          dut.clock.step()
          for (_ <- 1 to 10) {
            dut.io.in.ready.expect(false.B)
            dut.io.out.valid.expect(false.B)
            dut.clock.step()
          }
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.expect(out.U)
        }
      }
    }

    it should s"hold the output until out.ready when $direction" in {
      test(new AESCBC(128)) { dut =>
        setup(dut, spKey, spIv, decrypt)
        val in = blocks(from)
        val out = blocks(to)
        dut.io.out.ready.poke(false.B)
        dut.io.in.bits.data.poke(in(0).U)
        dut.io.in.bits.first.poke(true.B)
        dut.io.in.valid.poke(true.B)
        dut.clock.step()
        dut.io.in.bits.data.poke(in(1).U)
        dut.io.in.bits.first.poke(false.B)
        dut.clock.step(10)

        //nobody takes the output, so it stays and the next block has to wait
        for (_ <- 0 until 5) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.expect(out(0).U)
          dut.io.in.ready.expect(false.B)
          dut.clock.step()
        }

        //take it, the waiting block goes in at the same time and still chains correctly
        dut.io.out.ready.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(10)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.expect(out(1).U)
      }
    }
  }

  for (keySize <- Seq(128, 192, 256)) {
    behavior of s"AESCBC (AES-$keySize)"

    //every message starts again from a new IV, so this also checks the first flag
    it should "match the JDK for random keys, IVs and messages, encrypting and decrypting" in {
      test(new AESCBC(keySize)) { dut =>
        val messageGen = Gen.choose(1, 8).flatMap(n => bytesGen(n * 16))
        forAll(bytesGen(keySize / 8), bytesGen(16), messageGen, Gen.oneOf(false, true), minSuccessful(100)) {
          (key, iv, message, decrypt) =>
            setup(dut, key, bytesToBigInt(iv), decrypt)
            val out = processMessage(dut, message.grouped(16).map(bytesToBigInt).toSeq)
            assert(out.flatMap(bigIntToBytes(_, 16)) == cbcJdk(key, iv, message, decrypt))
        }
      }
    }

    for (name <- nistFiles) {
      it should s"pass the NIST AESAVS CBC $name test vectors" in {
        test(new AESCBC(keySize))(dut => runNistFile(dut, s"CBC$name$keySize.rsp"))
      }
    }
  }

  behavior of "AESCBC (AES-128)"

  //AESAVS Monte Carlo test: 1000 blocks in one message. The first input is the one in the record,
  //the second one is the IV, after that it is the output from two blocks before.
  //It is the same for encrypting and decrypting, the record holds the last output.
  for (decrypt <- Seq(false, true)) {
    val section = if (decrypt) "DECRYPT" else "ENCRYPT"
    it should s"pass the NIST AESAVS CBC Monte Carlo test, $section (first $mctRecords records)" in {
      test(new AESCBC(128)) { dut =>
        for (v <- NistVectors.load("CBCMCT128.rsp", section).take(mctRecords)) {
          val iv = v.iv.get
          setup(dut, v.key, iv, decrypt)
          val (start, expected) = if (decrypt) (v.ciphertext.head, v.plaintext.head) else (v.plaintext.head, v.ciphertext.head)
          val outputs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
          var input = start
          for (j <- 0 until 1000) {
            outputs += process(dut, input, j == 0)
            input = if (j == 0) iv else outputs(j - 1)
          }
          assert(outputs.last == expected, s"CBCMCT128.rsp [$section] COUNT = ${v.count}")
        }
      }
    }
  }
}
