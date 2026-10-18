import chisel3._
import chisel3.util._

//Full AES encryption of one 128-bit block: KeyExpansion + AESEncrypt.
//
//The round keys are calculated from io.key without any clock cycles, so io.key
//has to stay the same while a block is being encrypted (same as roundKeys in AESEncrypt).
//Everything else works like in AESEncrypt: Nr + 1 cycles from in.fire to out.valid.
class AES(val keySize: Int = 128) extends Module {
  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W)) //byte 0 of the key is the MSB, like in the NIST hex strings
    val in = Flipped(Decoupled(UInt(128.W))) //plaintext
    val out = Decoupled(UInt(128.W))         //ciphertext
  })

  val keyExpansion = Module(new KeyExpansion(keySize))
  val encrypt = Module(new AESEncrypt(keySize))

  keyExpansion.io.key := io.key
  encrypt.io.roundKeys := keyExpansion.io.roundKeys
  encrypt.io.in <> io.in
  io.out <> encrypt.io.out
}
