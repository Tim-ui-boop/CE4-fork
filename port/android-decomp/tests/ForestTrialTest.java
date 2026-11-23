package com.ast.re4decomp;
import java.io.*;
/** Fixture: four decoded retail bodies, stock.esl and donor.esl (US Disc 1). */
public class ForestTrialTest {
  public static void main(String[] args)throws Exception {
    File dir=new File(args[0]);byte[][] rooms=new byte[4][];
    for(int i=0;i<4;i++)rooms[i]=ForestTrial.read(new File(dir,ForestTrial.ROOMS[i]+".raw"),ForestTrial.SIZE[i]);
    byte[] esl=ForestTrial.read(new File(dir,"stock.esl"),8160),donor=ForestTrial.read(new File(dir,"donor.esl"),8160);
    byte[] orig=esl.clone();
    ForestTrial.patch(rooms,esl,donor);
    for(int i=0;i<4;i++)ForestTrial.verify(rooms[i],ForestTrial.RESULT[i]);
    if(esl[0x11*32+1]!=0x1B || esl[0x11*32+2]!=10 || esl[0x11*32+3]!=53) throw new AssertionError("crow slot is not a chainsaw actor");
    if(esl[0x27*32+1]!=0x10 || (esl[0x27*32+7]&8)==0) throw new AssertionError("ambush slot is not an armed chainsaw Ganado");
    ForestTrial.verify(esl,"5b5565db11c96e416a4e78b1b7011883c6fb4603b872255be6c79a22c53c68fb");
    try { ForestTrial.patch(rooms,orig,donor); throw new AssertionError("Accepted modified stock list"); } catch(IOException expected) {}
    System.out.println("PASS: retail rooms, chainsaw crow and Ganado, door and item changes, modified input refused");
  }
}
