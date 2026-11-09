import chisel3._
import chisel3.util._

//One block of the message going into AESCBC
class CBCBlock extends Bundle {
  val data = UInt(128.W) //plaintext when encrypting, ciphertext when decrypting
  val first = Bool()     //first block of a message: start again from io.iv
}

//AES in Cipher Block Chaining mode (NIST SP 800-38A 6.2).
//
//Encrypt: C_j = AES(P_j xor C_j-1)
//Decrypt: P_j = AES^-1(C_j) xor C_j-1
//with C_-1 = IV. Unlike CFB and OFB, decrypting needs the inverse cipher,
//so there is one AESEncrypt and one AESDecrypt core sharing the same KeyExpansion.
//
//key, iv and decrypt have to stay the same while a message is being processed.
//Every block takes Nr + 1 cycles from in.fire to out.valid. When encrypting the next block needs
//the ciphertext of this one, so only one block is worked on at a time (same for decrypting, to keep it simple).
//The next block can be taken in the same cycle the output is taken.
class AESCBC(val keySize: Int = 128) extends Module {
  val io = IO(new Bundle {
    val key = Input(UInt(keySize.W)) //byte 0 of the key is the MSB, like in the NIST hex strings
    val iv = Input(UInt(128.W))
    val decrypt = Input(Bool())
    val in = Flipped(Decoupled(new CBCBlock))
    val out = Decoupled(UInt(128.W)) //ciphertext when encrypting, plaintext when decrypting
  })

  val keyExpansion = Module(new KeyExpansion(keySize))
  val encrypt = Module(new AESEncrypt(keySize))
  val decrypt = Module(new AESDecrypt(keySize))
  keyExpansion.io.key := io.key
  encrypt.io.roundKeys := keyExpansion.io.roundKeys
  decrypt.io.roundKeys := keyExpansion.io.roundKeys

  val lastCipher = Reg(UInt(128.W)) //the newest ciphertext block we know of
  val chain = Reg(UInt(128.W))      //C_j-1 of the block being decrypted

  //C_j-1 for a new block: the IV for the first one of a message, otherwise the last ciphertext.
  //When encrypting, lastCipher is only written when the output is taken. If that happens
  //in this same cycle, it is not updated yet, so the encrypt output is used directly.
  //When decrypting, the ciphertext is the input, so lastCipher is already right.
  val prevCipher = Mux(!io.decrypt && encrypt.io.out.valid, encrypt.io.out.bits, lastCipher)
  val chainIn = Mux(io.in.bits.first, io.iv, prevCipher)

  encrypt.io.in.valid := io.in.valid && !io.decrypt
  encrypt.io.in.bits := io.in.bits.data ^ chainIn
  decrypt.io.in.valid := io.in.valid && io.decrypt
  decrypt.io.in.bits := io.in.bits.data
  io.in.ready := Mux(io.decrypt, decrypt.io.in.ready, encrypt.io.in.ready)

  io.out.valid := Mux(io.decrypt, decrypt.io.out.valid, encrypt.io.out.valid)
  io.out.bits := Mux(io.decrypt, decrypt.io.out.bits ^ chain, encrypt.io.out.bits)
  encrypt.io.out.ready := io.out.ready && !io.decrypt
  decrypt.io.out.ready := io.out.ready && io.decrypt

  when(encrypt.io.out.fire) {
    lastCipher := encrypt.io.out.bits
  }

  when(io.in.fire && io.decrypt) {
    chain := chainIn
    lastCipher := io.in.bits.data
  }
}
