import chisel3._
import chisel3.util._

object AESEncrypt {
  //Number of rounds Nr for each key size (FIPS-197 table 3)
  def rounds(keySize: Int): Int = keySize match {
    case 128 => 10
    case 192 => 12
    case 256 => 14
  }
}

//AES encryption of one 128-bit block (FIPS-197 5.1), one round per clock cycle.
//
//The round keys come in from outside (from KeyExpansion), so the only thing the key size
//changes here is the number of rounds. The same core works for AES-128, 192 and 256.
//
//Round key i is the State made from the words w[4i .. 4i+3] of the key schedule:
//roundKeys(i).rows(r)(c) = byte r of word w[4i + c] (byte 0 = most significant).
//The round keys must stay the same while a block is being encrypted.
//
//Timing: when in.fire, the block goes through round 0 (just AddRoundKey).
//Rounds 1..Nr then take one cycle each, so out.valid goes high Nr + 1 cycles after in.fire.
//out.bits is held until out.ready, and a new block can be taken in the same cycle.
class AESEncrypt(val keySize: Int = 128) extends Module {
  require(Seq(128, 192, 256).contains(keySize), "AES key size must be 128, 192 or 256")
  val nr = AESEncrypt.rounds(keySize)

  val io = IO(new Bundle {
    val roundKeys = Input(Vec(nr + 1, new State))
    val in = Flipped(Decoupled(UInt(128.W))) //plaintext
    val out = Decoupled(UInt(128.W))         //ciphertext
  })

  val state = Reg(new State)
  val round = RegInit(0.U(log2Ceil(nr + 1).W))
  val busy = RegInit(false.B)
  val done = RegInit(false.B)

  val subBytes = Module(new SubBytes)
  val shiftRows = Module(new ShiftRows)
  val mixColumns = Module(new MixColumns)
  val addRoundKey = Module(new AddRoundKey)

  //One round: SubBytes -> ShiftRows -> MixColumns -> AddRoundKey
  subBytes.io.in := state
  shiftRows.io.in := subBytes.io.out
  mixColumns.io.in := shiftRows.io.out

  //The last round skips MixColumns.
  //When not busy, AddRoundKey is used for round 0 on the new plaintext (round is 0 then).
  val roundOut = Mux(round === nr.U, shiftRows.io.out, mixColumns.io.out)
  addRoundKey.io.in := Mux(busy, roundOut, AESBlock.toState(io.in.bits))
  addRoundKey.io.key := io.roundKeys(round)

  io.in.ready := !busy && (!done || io.out.ready)
  io.out.valid := done
  io.out.bits := AESBlock.fromState(state)

  when(io.out.fire) {
    done := false.B
  }

  when(io.in.fire) {
    state := addRoundKey.io.out
    round := 1.U
    busy := true.B
  }.elsewhen(busy) {
    state := addRoundKey.io.out
    round := round + 1.U
    when(round === nr.U) {
      round := 0.U
      busy := false.B
      done := true.B
    }
  }
}
