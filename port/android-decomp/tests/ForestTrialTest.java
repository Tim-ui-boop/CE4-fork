package com.ast.re4decomp;
import java.io.*;
/** Pass a directory containing four decoded retail bodies rNNN.raw and stock.esl. */
public class ForestTrialTest {
  public static void main(String[] args)throws Exception {
    File dir=new File(args[0]);byte[][] rooms=new byte[4][];
    for(int i=0;i<4;i++)rooms[i]=ForestTrial.read(new File(dir,ForestTrial.ROOMS[i]+".raw"),ForestTrial.SIZE[i]);
    byte[] esl=ForestTrial.read(new File(dir,"stock.esl"),8160),orig=esl.clone();
    ForestTrial.patch(rooms,esl);
    for(int i=0;i<4;i++)ForestTrial.verify(rooms[i],ForestTrial.RESULT[i]);
    for(int i=0;i<8160;i++)if(esl[i]!=(i==0x4e2?4:orig[i]))throw new AssertionError();
    try { ForestTrial.patch(rooms,orig); throw new AssertionError("Accepted modified rooms"); } catch(IOException expected) {}
    System.out.println("PASS: all four retail room outputs, ambush variant only, modified input refused");
  }
}
