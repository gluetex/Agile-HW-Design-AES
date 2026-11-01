import chisel3._

//AES inverse S-box (FIPS-197 5.3.2)
//It undoes the S-box, InvSBox(SBox(b)) = b, so the table is just the SBox table turned around
object InvSBox {
  val table: Seq[Int] = (0 until 256).map(b => SBox.table.indexOf(b))

  //Same trick as in SBox: all 256 entries packed into one 2048-bit constant, entry k is bits 8k+7..8k
  private val packed: BigInt = table.zipWithIndex.map { case (v, k) => BigInt(v) << (8 * k) }.sum

  //Look up one byte
  def apply(in: UInt): UInt = (packed.U(2048.W) >> (in(7, 0) ## 0.U(3.W)))(7, 0)
}
