/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.backend.decode

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import xs.utils.{LookupTree, SignExt, ZeroExt}
import xs.utils.perf.HasPerfLogging
import freechips.rocketchip.util.uintToBitPat
import utils._
import xiangshan.ExceptionNO.illegalInstr
import xiangshan._
import freechips.rocketchip.rocket.Instructions._
import xiangshan.backend.execute.fu.alu.ALUOpType
import xiangshan.backend.execute.fu.bku.BKUOpType
import xiangshan.backend.execute.fu.csr.CSROpType
import xiangshan.backend.execute.fu.fence.FenceOpType
import xiangshan.backend.execute.fu.jmp.JumpOpType
import xiangshan.backend.execute.fu.mdu.MDUOpType

/**
 * Abstract trait giving defaults and other relevant values to different Decode constants/
 */
abstract trait DecodeConstants {
  // This X should be used only in 1-bit signal. Otherwise, use BitPat("b???") to align with the width of UInt.
  def X = BitPat("b?")
  def N = BitPat("b0")
  def Y = BitPat("b1")

  def decodeDefault: List[BitPat] = // illegal instruction
    //   srcType(0)  srcType(1)  srcType(2)  fuType    fuOpType    rfWen
    //   |           |           |           |         |           |  fpWen
    //   |           |           |           |         |           |  |  vdWen
    //   |           |           |           |         |           |  |  |  isXSTrap
    //   |           |           |           |         |           |  |  |  |  noSpecExec
    //   |           |           |           |         |           |  |  |  |  |  blockBackward
    //   |           |           |           |         |           |  |  |  |  |  |  flushPipe
    //   |           |           |           |         |           |  |  |  |  |  |  |  selImm
    //   |           |           |           |         |           |  |  |  |  |  |  |  |
    List(SrcType.DC, SrcType.DC, SrcType.DC, FuType.X, FuOpType.X, N, N, N, N, N, N, N, SelImm.INVALID_INSTR) // Use SelImm to indicate invalid instr

  val table: Array[(BitPat, List[BitPat])]
}

trait DecodeUnitConstants
{
  // abstract out instruction decode magic numbers
  val RD_MSB  = 11
  val RD_LSB  = 7
  val RS1_MSB = 19
  val RS1_LSB = 15
  val RS2_MSB = 24
  val RS2_LSB = 20
  val RS3_MSB = 31
  val RS3_LSB = 27
}

/**
 * Decoded control signals
 * See xiangshan/package.scala, xiangshan/backend/package.scala, Bundle.scala
 */

/**
 * Decode constants for RV64
 */
object X64Decode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(
    LD      -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.ld,  Y, N, N ,N, N, N, N, SelImm.IMM_I),
    LWU     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lwu, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SD      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.stu, LSUOpType.sd,  N, N, N, N, N, N, N, SelImm.IMM_S),

    SLLI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.sll, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SRLI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.srl, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SRAI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.sra, Y, N, N, N, N, N, N, SelImm.IMM_I),

    ADDIW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.addw, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SLLIW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.sllw, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SRAIW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.sraw, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SRLIW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.srlw, Y, N, N, N, N, N, N, SelImm.IMM_I),

    ADDW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.addw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SUBW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.subw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SLLW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sllw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SRAW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sraw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SRLW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.srlw, Y, N, N, N, N, N, N, SelImm.IMM_X),

    RORW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.rorw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    RORIW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.rorw, Y, N, N, N, N, N, N, SelImm.IMM_I),
    ROLW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.rolw, Y, N, N, N, N, N, N, SelImm.IMM_X)
  )
}

/**
 * Overall Decode constants
 */
object XDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(
    LW      -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lw,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    LH      -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lh,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    LHU     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lhu, Y, N, N, N, N, N, N, SelImm.IMM_I),
    LB      -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lb,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    LBU     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lbu, Y, N, N, N, N, N, N, SelImm.IMM_I),

    SW      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.stu, LSUOpType.sw,  N, N, N, N, N, N, N, SelImm.IMM_S),
    SH      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.stu, LSUOpType.sh,  N, N, N, N, N, N, N, SelImm.IMM_S),
    SB      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.stu, LSUOpType.sb,  N, N, N, N, N, N, N, SelImm.IMM_S),

    LUI     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.add, Y, N, N, N, N, N, N, SelImm.IMM_U),

    ADDI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.add, Y, N, N, N, N, N, N, SelImm.IMM_I),
    ANDI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.and, Y, N, N, N, N, N, N, SelImm.IMM_I),
    ORI     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.or,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    XORI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.xor, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SLTI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.slt, Y, N, N, N, N, N, N, SelImm.IMM_I),
    SLTIU   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.sltu, Y, N, N, N, N, N, N, SelImm.IMM_I),

    SLL     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sll,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    ADD     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.add,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SUB     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sub,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SLT     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.slt,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SLTU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sltu, Y, N, N, N, N, N, N, SelImm.IMM_X),
    AND     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.and,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    OR      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.or,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    XOR     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.xor,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SRA     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sra,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SRL     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.srl,  Y, N, N, N, N, N, N, SelImm.IMM_X),

    MUL     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mul, MDUOpType.mul,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    MULH    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mul, MDUOpType.mulh,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    MULHU   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mul, MDUOpType.mulhu,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    MULHSU  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mul, MDUOpType.mulhsu, Y, N, N, N, N, N, N, SelImm.IMM_X),
    MULW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mul, MDUOpType.mulw,   Y, N, N, N, N, N, N, SelImm.IMM_X),

    DIV     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.div,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    DIVU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.divu,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    REM     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.rem,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    REMU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.remu,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    DIVW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.divw,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    DIVUW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.divuw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    REMW    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.remw,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    REMUW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.div, MDUOpType.remuw, Y, N, N, N, N, N, N, SelImm.IMM_X),

    AUIPC   -> List(SrcType.pc , SrcType.imm, SrcType.DC, FuType.jmp, JumpOpType.auipc, Y, N, N, N, N, N, N, SelImm.IMM_U),
    JAL     -> List(SrcType.pc , SrcType.imm, SrcType.DC, FuType.jmp, JumpOpType.jal,   Y, N, N, N, N, N, N, SelImm.IMM_UJ),
    JALR    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.jmp, JumpOpType.jalr,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    BEQ     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.beq,    N, N, N, N, N, N, N, SelImm.IMM_SB),
    BNE     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bne,    N, N, N, N, N, N, N, SelImm.IMM_SB),
    BGE     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bge,    N, N, N, N, N, N, N, SelImm.IMM_SB),
    BGEU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bgeu,   N, N, N, N, N, N, N, SelImm.IMM_SB),
    BLT     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.blt,    N, N, N, N, N, N, N, SelImm.IMM_SB),
    BLTU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bltu,   N, N, N, N, N, N, N, SelImm.IMM_SB),

    // I-type, the immediate12 holds the CSR register.
    CSRRW   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.wrt, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    CSRRS   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.set, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    CSRRC   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.clr, Y, N, N, N, Y, Y, N, SelImm.IMM_I),

    CSRRWI  -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.wrti, Y, N, N, N, Y, Y, N, SelImm.IMM_Z),
    CSRRSI  -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.seti, Y, N, N, N, Y, Y, N, SelImm.IMM_Z),
    CSRRCI  -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.clri, Y, N, N, N, Y, Y, N, SelImm.IMM_Z),

    SFENCE_VMA->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.fence, FenceOpType.sfence, N, N, N, N, Y, Y, Y, SelImm.IMM_X),
    EBREAK  -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.jmp, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    ECALL   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.jmp, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    SRET    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.jmp, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    MRET    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.jmp, Y, N, N, N, Y, Y, N, SelImm.IMM_I),
    DRET    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.jmp, Y, N, N, N, Y, Y, N, SelImm.IMM_I),

    WFI     -> List(SrcType.pc, SrcType.imm, SrcType.DC, FuType.csr, CSROpType.wfi, Y, N, N, N, Y, Y, N, SelImm.IMM_X),

    FENCE_I -> List(SrcType.pc, SrcType.imm, SrcType.DC, FuType.fence, FenceOpType.fencei, N, N, N, N, Y, Y, Y, SelImm.IMM_X),
    FENCE   -> List(SrcType.pc, SrcType.imm, SrcType.DC, FuType.fence, FenceOpType.fence,  N, N, N, N, Y, Y, Y, SelImm.IMM_X),

    // A-type
    AMOADD_W-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoadd_w,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOXOR_W-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoxor_w,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOSWAP_W->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoswap_w, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOAND_W-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoand_w,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOOR_W -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoor_w,   Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMIN_W-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomin_w,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMINU_W->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amominu_w, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMAX_W-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomax_w,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMAXU_W->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomaxu_w, Y, N, N, N, Y, Y, N, SelImm.IMM_X),

    AMOADD_D-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoadd_d,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOXOR_D-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoxor_d,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOSWAP_D->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoswap_d, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOAND_D-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoand_d,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOOR_D -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amoor_d,   Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMIN_D-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomin_d,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMINU_D->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amominu_d, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMAX_D-> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomax_d,  Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    AMOMAXU_D->List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.amomaxu_d, Y, N, N, N, Y, Y, N, SelImm.IMM_X),

    LR_W    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.mou, LSUOpType.lr_w, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    LR_D    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.mou, LSUOpType.lr_d, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    SC_W    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.sc_w, Y, N, N, N, Y, Y, N, SelImm.IMM_X),
    SC_D    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.mou, LSUOpType.sc_d, Y, N, N, N, Y, Y, N, SelImm.IMM_X),

    ANDN    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.andn, Y, N, N, N, N, N, N, SelImm.IMM_X),
    ORN     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.orn,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    XNOR    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.xnor, Y, N, N, N, N, N, N, SelImm.IMM_X),
    ORC_B   -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.alu, ALUOpType.orcb, Y, N, N, N, N, N, N, SelImm.IMM_X),

    MIN     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.min,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    MINU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.minu, Y, N, N, N, N, N, N, SelImm.IMM_X),
    MAX     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.max,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    MAXU    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.maxu, Y, N, N, N, N, N, N, SelImm.IMM_X),

    SEXT_B  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.alu, ALUOpType.sextb, Y, N, N, N, N, N, N, SelImm.IMM_X),
    PACKH   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.packh, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SEXT_H  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.alu, ALUOpType.sexth, Y, N, N, N, N, N, N, SelImm.IMM_X),
    PACKW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.packw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    BREV8   -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.alu, ALUOpType.revb, Y, N, N, N, N, N, N, SelImm.IMM_X),
    REV8    -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.alu, ALUOpType.rev8, Y, N, N, N, N, N, N, SelImm.IMM_X),
    PACK    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.pack, Y, N, N, N, N, N, N, SelImm.IMM_X),

    BSET    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bset, Y, N, N, N, N, N, N, SelImm.IMM_X),
    BSETI   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.bset, Y, N, N, N, N, N, N, SelImm.IMM_I),
    BCLR    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bclr, Y, N, N, N, N, N, N, SelImm.IMM_X),
    BCLRI   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.bclr, Y, N, N, N, N, N, N, SelImm.IMM_I),
    BINV    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.binv, Y, N, N, N, N, N, N, SelImm.IMM_X),
    BINVI   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.binv, Y, N, N, N, N, N, N, SelImm.IMM_I),
    BEXT    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.bext, Y, N, N, N, N, N, N, SelImm.IMM_X),
    BEXTI   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.bext, Y, N, N, N, N, N, N, SelImm.IMM_I),

    ROR     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.ror, Y, N, N, N, N, N, N, SelImm.IMM_X),
    RORI    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.ror, Y, N, N, N, N, N, N, SelImm.IMM_I),
    ROL     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.rol, Y, N, N, N, N, N, N, SelImm.IMM_X),

    SH1ADD  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh1add, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SH2ADD  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh2add, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SH3ADD  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh3add, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SH1ADD_UW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh1adduw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SH2ADD_UW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh2adduw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SH3ADD_UW   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.sh3adduw, Y, N, N, N, N, N, N, SelImm.IMM_X),
    ADD_UW      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.alu, ALUOpType.adduw,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    SLLI_UW     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.slliuw,   Y, N, N, N, N, N, N, SelImm.IMM_I)
  )
}

/**
 * FP Decode constants
 */
object FDecode extends DecodeConstants{
  val table: Array[(BitPat, List[BitPat])] = Array(

  FLW     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.lw, N, Y, N, N, N, N, N, SelImm.IMM_I),
  FLD     -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.ldu, LSUOpType.ld, N, Y, N, N, N, N, N, SelImm.IMM_I),
  FSW     -> List(SrcType.reg, SrcType.fp,  SrcType.DC, FuType.stu, LSUOpType.sw, N, N, N, N, N, N, N, SelImm.IMM_S),
  FSD     -> List(SrcType.reg, SrcType.fp,  SrcType.DC, FuType.stu, LSUOpType.sd, N, N, N, N, N, N, N, SelImm.IMM_S),

  FCLASS_S-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCLASS_D-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),

  FMV_D_X -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f,   FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMV_X_D -> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i,   FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FMV_X_W -> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i,   FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FMV_W_X -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f,   FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  FSGNJ_S -> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSGNJ_D -> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSGNJX_S-> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSGNJX_D-> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSGNJN_S-> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSGNJN_D-> List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  // FP to FP
  FCVT_S_D-> List(SrcType.fp, SrcType.imm, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_D_S-> List(SrcType.fp, SrcType.imm, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  // Int to FP
  FCVT_S_W-> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_S_WU->List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_S_L-> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_S_LU->List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  FCVT_D_W-> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_D_WU->List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_D_L-> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FCVT_D_LU->List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.i2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  // FP to Int
  FCVT_W_S-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_WU_S->List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_L_S-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_LU_S->List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),

  FCVT_W_D-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_WU_D->List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_L_D-> List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FCVT_LU_D->List(SrcType.fp , SrcType.imm, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),

  // "fp_single" is used for wb_data formatting (and debugging)
  FEQ_S    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FLT_S    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FLE_S    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),

  FEQ_D    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FLT_D    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
  FLE_D    ->List(SrcType.fp , SrcType.fp, SrcType.DC, FuType.f2i, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),

  FMIN_S   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMAX_S   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMIN_D   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMAX_D   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.f2f, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  FADD_S   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSUB_S   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMUL_S   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FADD_D   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FSUB_D   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMUL_D   ->List(SrcType.fp,  SrcType.fp, SrcType.DC, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),

  FMADD_S  ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMSUB_S  ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FNMADD_S ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FNMSUB_S ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMADD_D  ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FMSUB_D  ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FNMADD_D ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
  FNMSUB_D ->List(SrcType.fp,  SrcType.fp, SrcType.fp, FuType.fmac, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X)
  )
}

/**
  * Bit Manipulation Decode
  */
object BDecode extends DecodeConstants{
  val table: Array[(BitPat, List[BitPat])] = Array(
    // Basic bit manipulation
    CLZ     -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.clz,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    CTZ     -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.ctz,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    CPOP    -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.cpop,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    XPERM8  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.xpermb, Y, N, N, N, N, N, N, SelImm.IMM_X),
    XPERM4  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.xpermn, Y, N, N, N, N, N, N, SelImm.IMM_X),

    CLZW    -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.bku, BKUOpType.clzw,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    CTZW    -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.bku, BKUOpType.ctzw,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    CPOPW   -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.bku, BKUOpType.cpopw, Y, N, N, N, N, N, N, SelImm.IMM_X),

    CLMUL   -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.clmul,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    CLMULH  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.clmulh, Y, N, N, N, N, N, N, SelImm.IMM_X),
    CLMULR  -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.clmulr, Y, N, N, N, N, N, N, SelImm.IMM_X),

    AES64ES     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.aes64es,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    AES64ESM    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.aes64esm,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    AES64DS     -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.aes64ds,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    AES64DSM    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.aes64dsm,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    AES64IM     -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.aes64im,    Y, N, N, N, N, N, N, SelImm.IMM_X),
    AES64KS1I   -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.bku, BKUOpType.aes64ks1i,  Y, N, N, N, N, N, N, SelImm.IMM_I),
    AES64KS2    -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.aes64ks2,   Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA256SUM0  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha256sum0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA256SUM1  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha256sum1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA256SIG0  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha256sig0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA256SIG1  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha256sig1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA512SUM0  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha512sum0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA512SUM1  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha512sum1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA512SIG0  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha512sig0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SHA512SIG1  -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sha512sig1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM3P0       -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sm3p0,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM3P1       -> List(SrcType.reg, SrcType.DC,  SrcType.DC, FuType.bku, BKUOpType.sm3p1,  Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4KS0      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ks0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4KS1      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ks1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4KS2      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ks2, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4KS3      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ks3, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4ED0      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ed0, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4ED1      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ed1, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4ED2      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ed2, Y, N, N, N, N, N, N, SelImm.IMM_X),
    SM4ED3      -> List(SrcType.reg, SrcType.reg, SrcType.DC, FuType.bku, BKUOpType.sm4ed3, Y, N, N, N, N, N, N, SelImm.IMM_X),
  )
}

/**
 * FP Divide SquareRoot Constants
 */
object FDivSqrtDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(
    FDIV_S    ->List(SrcType.fp,  SrcType.fp,  SrcType.DC, FuType.fDivSqrt, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    FDIV_D    ->List(SrcType.fp,  SrcType.fp,  SrcType.DC, FuType.fDivSqrt, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    FSQRT_S   ->List(SrcType.fp,  SrcType.imm, SrcType.DC, FuType.fDivSqrt, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    FSQRT_D   ->List(SrcType.fp,  SrcType.imm, SrcType.DC, FuType.fDivSqrt, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X)
  )
}
/*
 * CBO decode
 */
object CBODecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(
    CBO_ZERO  -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.stu, LSUOpType.cbo_zero , N, N, N, N, N, N, N, SelImm.IMM_S),
    CBO_CLEAN -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.stu, LSUOpType.cbo_clean, N, N, N, N, N, N, N, SelImm.IMM_S),
    CBO_FLUSH -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.stu, LSUOpType.cbo_flush, N, N, N, N, N, N, N, SelImm.IMM_S),
    CBO_INVAL -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.stu, LSUOpType.cbo_inval, N, N, N, N, N, N, N, SelImm.IMM_S)
  )
}

object VectorArithDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(

    //arithmetic instruction
    VADC_VIM ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VADC_VVM ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VADC_VXM ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VADD_VI ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VADD_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VADD_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAND_VI ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VAND_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAND_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLL_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSLL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSRA_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSRA_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSRA_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSRL_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSRL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSRL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSUB_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VMAX_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMAX_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMAXU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMAXU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMERGE_VIM -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMERGE_VVM -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMERGE_VXM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMIN_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMIN_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMINU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMINU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VMV1R_V -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV2R_V -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV4R_V -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV8R_V -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV_S_X -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.s2v, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV_V_I -> List(SrcType.imm, SrcType.DC, SrcType.DC, FuType.s2v, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VMV_V_V -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV_V_X -> List(SrcType.reg, SrcType.DC, SrcType.DC, FuType.s2v, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMV_X_S -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.valu, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
    VOR_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VOR_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VOR_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSBC_VVM -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSBC_VXM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSEXT_VF2 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSEXT_VF4 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSEXT_VF8 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VXOR_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VXOR_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VXOR_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VZEXT_VF2 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VZEXT_VF4 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VZEXT_VF8 -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    // fixed-point Instruction
    VASUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VASUB_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VASUBU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VASUBU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAADD_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAADD_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAADDU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VAADDU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSRA_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSSRA_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSRA_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSRL_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSSRL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSRL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSUB_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSUBU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSSUBU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSADD_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSADD_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSADD_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSADDU_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSADDU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSADDU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),


    //mac
    VMUL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMUL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULH_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULH_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULHSU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULHSU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULHU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMULHU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMACC_VV -> List(SrcType.vec, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMACC_VX -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNMSAC_VV -> List(SrcType.vec, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNMSAC_VX -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADD_VV -> List(SrcType.vec, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADD_VX -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNMSUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNMSUB_VX -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSMUL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSMUL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),


    //float
    VFADD_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFADD_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCLASS_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_F_X_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_F_XU_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_RTZ_X_F_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_RTZ_XU_F_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_X_F_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFCVT_XU_F_V ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMACC_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMACC_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMADD_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMADD_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMAX_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMAX_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMERGE_VFM ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMIN_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMIN_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMSAC_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMSAC_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMSUB_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMSUB_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMUL_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMUL_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMV_F_S -> List(SrcType.vec, SrcType.DC, SrcType.DC, FuType.vfp, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    VFMV_S_F -> List(SrcType.fp, SrcType.DC, SrcType.DC, FuType.s2v, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFMV_V_F -> List(SrcType.fp, SrcType.DC, SrcType.DC, FuType.s2v, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMACC_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMACC_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMADD_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMADD_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMSAC_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMSAC_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMSUB_VF ->List(SrcType.fp,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNMSUB_VV ->List(SrcType.vec,  SrcType.vec, SrcType.vec, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFREC7_V ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    //reduction-float
    VFREDMAX_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFREDMIN_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFREDOSUM_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFREDUSUM_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VFRSQRT7_V ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFRSUB_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJ_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJ_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJN_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJN_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJX_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSGNJX_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSUB_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSUB_VV ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),


    //div
    VFDIV_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFDIV_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFRDIV_VF ->List(SrcType.fp,  SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSQRT_V ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VDIV_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VDIV_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VDIVU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VDIVU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREM_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREM_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREMU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREMU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vdiv, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    //mask
    VMXNOR_MM ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMXOR_MM ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMAND_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMANDN_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMNAND_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMNOR_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMOR_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMORN_MM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFIRST_M -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
    VCPOP_M -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, Y, N, N, N, N, N, N, SelImm.IMM_X),
    VMSBF_M -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSIF_M -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSOF_M -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VID_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VIOTA_M -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmask, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),


    //reduction
    VREDAND_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDMAX_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDMAXU_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDMIN_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDMINU_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDOR_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDSUM_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VREDXOR_VS ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VRSUB_VI ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VRSUB_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),


    //permutation instructions
    VCOMPRESS_VM ->List(SrcType.vec,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLIDE1DOWN_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLIDE1UP_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLIDEDOWN_VI ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSLIDEDOWN_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VSLIDEUP_VI ->List(SrcType.imm,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VSLIDEUP_VX ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSLIDE1DOWN_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFSLIDE1UP_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VRGATHER_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VRGATHER_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VRGATHER_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VRGATHEREI16_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vpermu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
  )
}


object VectorLoadDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(

    VL1RE16_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL1RE32_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL1RE64_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL1RE8_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL2RE16_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL2RE32_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL2RE64_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL2RE8_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL4RE16_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL4RE32_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL4RE64_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL4RE8_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL8RE16_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL8RE32_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL8RE64_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VL8RE8_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE16_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE16FF_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE32_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE32FF_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE64_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE64FF_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE8_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLE8FF_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLM_V ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLOXEI16_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLOXEI32_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLOXEI64_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLOXEI8_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLSE16_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLSE32_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLSE64_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLSE8_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLUXEI16_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLUXEI32_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLUXEI64_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VLUXEI8_V ->List(SrcType.reg,  SrcType.vec, SrcType.DC, FuType.ldu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

  )
}

object VectorStoreDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(

    VSE16_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSE32_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSE64_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSE8_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSM_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSOXEI16_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSOXEI32_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSOXEI64_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSOXEI8_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSSE16_V -> List(SrcType.reg, SrcType.reg, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSSE32_V -> List(SrcType.reg, SrcType.reg, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSSE64_V -> List(SrcType.reg, SrcType.reg, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSSE8_V -> List(SrcType.reg, SrcType.reg, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSUXEI16_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSUXEI32_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSUXEI64_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VSUXEI8_V -> List(SrcType.reg, SrcType.vec, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VS1R_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VS2R_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VS4R_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),
    VS8R_V -> List(SrcType.reg, SrcType.DC, SrcType.vec, FuType.stu, FuOpType.X, N, N, N, N, N, N, N, SelImm.IMM_X),

  )
}

object VectorConfDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(
    VSETIVLI ->List(SrcType.imm,  SrcType.imm, SrcType.DC, FuType.csr, CSROpType.vsetivli, Y, N, N, N, N, N, N, SelImm.IMM_CI),
    VSETVL ->List(SrcType.reg,  SrcType.reg, SrcType.DC, FuType.csr, CSROpType.vsetvl, Y, N, N, N, N, N, N, SelImm.IMM_X),
    VSETVLI ->List(SrcType.reg,  SrcType.imm, SrcType.DC, FuType.csr, CSROpType.vsetvli, Y, N, N, N, N, N, N, SelImm.IMM_C),
  )
}

object VectorWidenDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(

    //widening
    VWADD_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADD_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADD_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADD_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADDU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADDU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADDU_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWADDU_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUB_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUB_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUB_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUBU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUBU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUBU_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWSUBU_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMUL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMUL_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMULSU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMULSU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMULU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMULU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.vmac, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMACC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMACC_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMACCSU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    VWMACCSU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, Y, N, N, N, N, N, SelImm.IMM_X),
    VWMACCU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMACCU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWMACCUS_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VFWADD_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWADD_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWADD_WF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWADD_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWSUB_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWSUB_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWSUB_WF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWSUB_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMUL_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMUL_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMACC_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMACC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWNMACC_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWNMACC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMSAC_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWMSAC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWNMSAC_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWNMSAC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_F_F_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_F_X_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_F_XU_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_RTZ_X_F_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_RTZ_XU_F_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_X_F_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWCVT_XU_F_V -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VWREDSUM_VS -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VWREDSUMU_VS -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vreduc, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWREDOSUM_VS -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFWREDUSUM_VS -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

  )
}

object VectorNarrowDecode extends DecodeConstants {
  val table: Array[(BitPat, List[BitPat])] = Array(

    //narrowing
    VNSRA_WI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VNSRA_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNSRA_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNSRL_WI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VNSRL_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNSRL_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNCLIP_WI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VNCLIP_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNCLIP_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNCLIPU_WI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_VA),
    VNCLIPU_WV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VNCLIPU_WX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_F_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_F_X_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_F_XU_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_ROD_F_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_RTZ_X_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_RTZ_XU_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_X_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VFNCVT_XU_F_W -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VMADC_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADC_VIM -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADC_VVM -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADC_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMADC_VXM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSBC_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSBC_VVM -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSBC_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSBC_VXM -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),

    VMSEQ_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSEQ_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSEQ_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSGT_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSGT_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSGTU_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSGTU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLE_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLE_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLE_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLEU_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLEU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLEU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLT_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLT_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLTU_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSLTU_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSNE_VI -> List(SrcType.imm, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSNE_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMSNE_VX -> List(SrcType.reg, SrcType.vec, SrcType.DC, FuType.valu, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFEQ_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFEQ_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFGE_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFGT_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFLE_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFLE_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFLT_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFLT_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFNE_VF -> List(SrcType.fp, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X),
    VMFNE_VV -> List(SrcType.vec, SrcType.vec, SrcType.DC, FuType.vfp, FuOpType.X, N, N, Y, N, N, N, N, SelImm.IMM_X)
  )
}


/**
 * XiangShan Trap Decode constants
 */
object XSTrapDecode extends DecodeConstants {
  def TRAP = BitPat("b000000000000?????000000001101011")
  val table: Array[(BitPat, List[BitPat])] = Array(
    TRAP    -> List(SrcType.reg, SrcType.imm, SrcType.DC, FuType.alu, ALUOpType.add, Y, N, Y, Y, Y, N, N, SelImm.IMM_I)
  )
}

//object Imm32Gen {
//  def apply(sel: UInt, inst: UInt) = {
//    val sign = Mux(sel === SelImm.IMM_Z, 0.S, inst(31).asSInt)
//    val b30_20 = Mux(sel === SelImm.IMM_U, inst(30,20).asSInt, sign)
//    val b19_12 = Mux(sel =/= SelImm.IMM_U && sel =/= SelImm.IMM_UJ, sign, inst(19,12).asSInt)
//    val b11 = Mux(sel === SelImm.IMM_U || sel === SelImm.IMM_Z, 0.S,
//              Mux(sel === SelImm.IMM_UJ, inst(20).asSInt,
//              Mux(sel === SelImm.IMM_SB, inst(7).asSInt, sign)))
//    val b10_5 = Mux(sel === SelImm.IMM_U || sel === SelImm.IMM_Z, 0.U(1.W), inst(30,25))
//    val b4_1 = Mux(sel === SelImm.IMM_U, 0.U(1.W),
//               Mux(sel === SelImm.IMM_S || sel === SelImm.IMM_SB, inst(11,8),
//               Mux(sel === SelImm.IMM_Z, inst(19,16), inst(24,21))))
//    val b0 = Mux(sel === SelImm.IMM_S, inst(7),
//             Mux(sel === SelImm.IMM_I, inst(20),
//             Mux(sel === SelImm.IMM_Z, inst(15), 0.U(1.W))))
//
//    Cat(sign, b30_20, b19_12, b11, b10_5, b4_1, b0)
//  }
//}

abstract class Imm(val len: Int) extends Bundle {
  def toImm32(minBits: UInt): UInt = do_toImm32(minBits(len - 1, 0))
  def do_toImm32(minBits: UInt): UInt
  def minBitsFromInstr(instr: UInt): UInt
}

case class Imm_I() extends Imm(12) {
  override def do_toImm32(minBits: UInt): UInt = SignExt(minBits(len - 1, 0), 32)

  override def minBitsFromInstr(instr: UInt): UInt =
    Cat(instr(31, 20))
}

case class Imm_S() extends Imm(12) {
  override def do_toImm32(minBits: UInt): UInt = SignExt(minBits, 32)

  override def minBitsFromInstr(instr: UInt): UInt =
    Cat(instr(31, 25), instr(11, 7))
}

case class Imm_B() extends Imm(12) {
  override def do_toImm32(minBits: UInt): UInt = SignExt(Cat(minBits, 0.U(1.W)), 32)

  override def minBitsFromInstr(instr: UInt): UInt =
    Cat(instr(31), instr(7), instr(30, 25), instr(11, 8))
}

case class Imm_U() extends Imm(20){
  override def do_toImm32(minBits: UInt): UInt = Cat(minBits(len - 1, 0), 0.U(12.W))

  override def minBitsFromInstr(instr: UInt): UInt = {
    instr(31, 12)
  }
}

case class Imm_J() extends Imm(20){
  override def do_toImm32(minBits: UInt): UInt = SignExt(Cat(minBits, 0.U(1.W)), 32)

  override def minBitsFromInstr(instr: UInt): UInt = {
    Cat(instr(31), instr(19, 12), instr(20), instr(30, 25), instr(24, 21))
  }
}

case class Imm_Z() extends Imm(12 + 5){
  override def do_toImm32(minBits: UInt): UInt = minBits

  override def minBitsFromInstr(instr: UInt): UInt = {
    Cat(instr(19, 15), instr(31, 20))
  }
}

case class Imm_B6() extends Imm(6){
  override def do_toImm32(minBits: UInt): UInt = ZeroExt(minBits, 32)

  override def minBitsFromInstr(instr: UInt): UInt = {
    instr(25, 20)
  }
}

case class Imm_VI() extends Imm(5) {
  override def do_toImm32(minBits: UInt): UInt = SignExt(minBits(len - 1, 0), 32)
  override def minBitsFromInstr(instr: UInt): UInt = Cat(instr(24, 20))
}

case class Imm_C() extends Imm(11) {
  override def do_toImm32(minBits: UInt): UInt = ZeroExt(Cat(minBits, 0.U(1.W)), 32)

  override def minBitsFromInstr(instr: UInt): UInt = Cat(instr(30, 20))
}

case class Imm_CI() extends Imm(15) {
  override def do_toImm32(minBits: UInt): UInt = ZeroExt(Cat(minBits, 0.U(1.W)), 32)
  override def minBitsFromInstr(instr: UInt): UInt = Cat(instr(29, 20), instr(19,15))
}

object ImmUnion {
  val I = Imm_I()
  val S = Imm_S()
  val B = Imm_B()
  val U = Imm_U()
  val J = Imm_J()
  val Z = Imm_Z()
  val B6 = Imm_B6()
  val VC = Imm_C()
  val VCI = Imm_CI()
  val VA = Imm_VI()
  val imms = Seq(I, S, B, U, J, Z, B6, VC, VCI, VA)
  val maxLen = imms.maxBy(_.len).len
  val immSelMap = Seq(
    SelImm.IMM_I,
    SelImm.IMM_S,
    SelImm.IMM_SB,
    SelImm.IMM_U,
    SelImm.IMM_UJ,
    SelImm.IMM_Z,
    SelImm.IMM_B6,
    SelImm.IMM_C,
    SelImm.IMM_CI,
    SelImm.IMM_VA
  ).zip(imms)
  println(s"ImmUnion max len: $maxLen")
}

case class Imm_LUI_LOAD() {
  def immFromLuiLoad(lui_imm: UInt, load_imm: UInt): UInt = {
    val loadImm = load_imm(Imm_I().len - 1, 0)
    Cat(lui_imm(Imm_U().len - loadImm.getWidth - 1, 0), loadImm)
  }
  def getLuiImm(uop: MicroOp): UInt = {
    val loadImmLen = Imm_I().len
    val imm_u = Cat(uop.psrc(1), uop.psrc(0), uop.ctrl.imm(ImmUnion.maxLen - 1, loadImmLen))
    Imm_U().do_toImm32(imm_u)
  }
}

/**
 * IO bundle for the Decode unit
 */
class DecodeUnitIO(implicit p: Parameters) extends XSBundle {
  val enq = new Bundle { val ctrl_flow = Input(new CtrlFlow) }
  val deq = new Bundle { val cf_ctrl = Output(new CfCtrl) }
  val csrCtrl = Input(new CustomCSRCtrlIO)
}

/**
 * Decode unit that takes in a single CtrlFlow and generates a CfCtrl.
 */
class DecodeUnit(implicit p: Parameters) extends XSModule with DecodeUnitConstants with HasPerfLogging{
  val io = IO(new DecodeUnitIO)

  val ctrl_flow = Wire(new CtrlFlow) // input with RVC Expanded
  val cf_ctrl = Wire(new CfCtrl)
  cf_ctrl.vCsrInfo := DontCare
  cf_ctrl.vctrl := DontCare
  ctrl_flow := io.enq.ctrl_flow

  val vdecode_table = VectorArithDecode.table ++
    VectorStoreDecode.table ++
    VectorLoadDecode.table ++
    VectorWidenDecode.table ++
    VectorNarrowDecode.table

  val decode_table = XDecode.table ++
    FDecode.table ++
    FDivSqrtDecode.table ++
    X64Decode.table ++
    XSTrapDecode.table ++
    BDecode.table ++
    CBODecode.table ++
    VectorConfDecode.table ++
    vdecode_table

//    ++ SvinvalDecode.table
  // assertion for LUI: only LUI should be assigned `selImm === SelImm.IMM_U && fuType === FuType.alu`
  val luiMatch = (t: Seq[BitPat]) => t(3).value == FuType.alu.litValue && t.reverse.head.value == SelImm.IMM_U.litValue
  val luiTable = decode_table.filter(t => luiMatch(t._2)).map(_._1).distinct
  assert(luiTable.length == 1 && luiTable.head == LUI, "Conflicts: LUI is determined by FuType and SelImm in Dispatch")

  // output
  cf_ctrl.cf := ctrl_flow
  val cs = Wire(new CtrlSignals)
  // val scs = Wire(new CtrlSignals)
  // val vcs = Wire(new CtrlSignals)
//  scs := 0.U.asTypeOf(new CtrlSignals()).decode(ctrl_flow.instr, decode_table)
  cs := Wire(new CtrlSignals()).decode(ctrl_flow.instr, decode_table)

  val isvectorload = BitPat("b???????_?????_????0_000_?????_0000111") === ctrl_flow.instr || BitPat("b???????_?????_????0_101_?????_0000111") === ctrl_flow.instr || BitPat("b???????_?????_????0_110_?????_0000111") === ctrl_flow.instr || BitPat("b???????_?????_????0_111_?????_0000111") === ctrl_flow.instr
  val isvectorstore = BitPat("b???????_?????_????0_000_?????_0100111") === ctrl_flow.instr || BitPat("b???????_?????_????0_101_?????_0100111") === ctrl_flow.instr || BitPat("b???????_?????_????0_110_?????_0100111") === ctrl_flow.instr || BitPat("b???????_?????_????0_111_?????_0100111") === ctrl_flow.instr
  val isVtype  = BitPat("b???????_?????_?????_111_?????_1010111") === ctrl_flow.instr
  val isVector = (BitPat("b???????_?????_?????_???_?????_1010111") === ctrl_flow.instr || isvectorload || isvectorstore) && !isVtype

  // vcs := Wire(new CtrlSignals()).decodev(ctrl_flow.instr, vdecode_table)

  // cs := Mux(isVector, vcs, scs)
  // cs := scs
  cs.isVector := isVector
  cs.isVtype := isVtype

  cs.singleStep := false.B
  cs.replayInst := false.B

  val fpDecoder = Module(new FPDecoder)
  fpDecoder.io.instr := ctrl_flow.instr
  cs.fpu := fpDecoder.io.fpCtrl

  val isMove = BitPat("b000000000000_?????_000_?????_0010011") === ctrl_flow.instr
  cs.isMove := isMove && ctrl_flow.instr(RD_MSB, RD_LSB) =/= 0.U && !io.csrCtrl.singlestep && io.csrCtrl.move_elim_enable

  // read src1~3 location
  cs.lsrc(0) := ctrl_flow.instr(RS1_MSB, RS1_LSB)
  cs.lsrc(1) := ctrl_flow.instr(RS2_MSB, RS2_LSB)
  cs.lsrc(2) := Mux(isVector, ctrl_flow.instr(RD_MSB, RD_LSB), ctrl_flow.instr(RS3_MSB, RS3_LSB))
  // read dest location
  cs.ldest := ctrl_flow.instr(RD_MSB, RD_LSB)

  // fill in exception vector
  cf_ctrl.cf.exceptionVec := io.enq.ctrl_flow.exceptionVec
  cf_ctrl.cf.exceptionVec(illegalInstr) := cs.selImm === SelImm.INVALID_INSTR

  when (!io.csrCtrl.svinval_enable) {
    val base_ii = cs.selImm === SelImm.INVALID_INSTR
    val sinval = BitPat("b0001011_?????_?????_000_00000_1110011") === ctrl_flow.instr
    val w_inval = BitPat("b0001100_00000_00000_000_00000_1110011") === ctrl_flow.instr
    val inval_ir = BitPat("b0001100_00001_00000_000_00000_1110011") === ctrl_flow.instr
    val svinval_ii = sinval || w_inval || inval_ir
    cf_ctrl.cf.exceptionVec(illegalInstr) := base_ii || svinval_ii
    cs.flushPipe := false.B
  }

  // fix frflags
  //                           fflags    zero csrrs rd    csr
  val isFrflags = BitPat("b000000000001_00000_010_?????_1110011") === ctrl_flow.instr
  when (cs.fuType === FuType.csr && isFrflags) {
    cs.blockBackward := false.B
  }

  cs.imm := LookupTree(cs.selImm, ImmUnion.immSelMap.map(
    x => {
      val minBits = x._2.minBitsFromInstr(ctrl_flow.instr)
      require(minBits.getWidth == x._2.len)
      x._1 -> minBits
    }
  ))

  cf_ctrl.ctrl := cs

  io.deq.cf_ctrl := cf_ctrl

  //-------------------------------------------------------------
  // Debug Info
  XSDebug("in:  instr=%x pc=%x excepVec=%b crossPageIPFFix=%d\n",
    io.enq.ctrl_flow.instr, io.enq.ctrl_flow.pc, io.enq.ctrl_flow.exceptionVec.asUInt,
    io.enq.ctrl_flow.crossPageIPFFix)
  XSDebug("out: srcType(0)=%b srcType(1)=%b srcType(2)=%b lsrc(0)=%d lsrc(1)=%d lsrc(2)=%d ldest=%d fuType=%b fuOpType=%b\n",
    io.deq.cf_ctrl.ctrl.srcType(0), io.deq.cf_ctrl.ctrl.srcType(1), io.deq.cf_ctrl.ctrl.srcType(2),
    io.deq.cf_ctrl.ctrl.lsrc(0), io.deq.cf_ctrl.ctrl.lsrc(1), io.deq.cf_ctrl.ctrl.lsrc(2),
    io.deq.cf_ctrl.ctrl.ldest, io.deq.cf_ctrl.ctrl.fuType, io.deq.cf_ctrl.ctrl.fuOpType)
  XSDebug("out: rfWen=%d fpWen=%d isXSTrap=%d noSpecExec=%d isBlocked=%d flushPipe=%d imm=%x\n",
    io.deq.cf_ctrl.ctrl.rfWen, io.deq.cf_ctrl.ctrl.fpWen, io.deq.cf_ctrl.ctrl.isXSTrap,
    io.deq.cf_ctrl.ctrl.noSpecExec, io.deq.cf_ctrl.ctrl.blockBackward, io.deq.cf_ctrl.ctrl.flushPipe,
    io.deq.cf_ctrl.ctrl.imm)
  XSDebug("out: excepVec=%b\n", io.deq.cf_ctrl.cf.exceptionVec.asUInt)
}
