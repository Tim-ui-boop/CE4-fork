package com.ast.re4decomp;

import java.io.*;
import java.security.*;

/** Fixed seed-100 US retail fixture. The ESL is published last as the activation marker. */
final class ForestTrial {
  static final String[] ROOMS={"r100","r101","r102","r108"};
  static final int[] SIZE={4669312,5467040,3985312,4712928};
  private static final String[] STOCK={"44b4cf277deb6376d40a65322162e34f0970a0912442b1a543952706612741e3","463b1b522749d317f1579ebe213a25af9b7ecf8d786840cd20a7961357f85f9b","287ba4aaaa5dd0911743e65c50b432a47d3c94553c2e058070f5a85c354a0e37","9c0f69910ea689ab42b4d7fb494fa8df94b37356cd590ad77711070f4ca3b193"};
  static final String[] RESULT={"29631b4da549b9c2940b1b11b791be08ffe5a83f46dcc907b5384eee6c03dbfc","d65d03b014230c7f88819718cfe2cce11c5b3e7005ac31fd8ba7ba3110343def","39a48f22005a1474bed9acb82663c18f81b1be957e4770be072810eb7178922e","99893265c427455a6fd0452723c8147a2fd845edf004774efebd666723527c90"};
  static void verify(byte[] bytes,String hash) throws IOException {
    try { byte[] d=MessageDigest.getInstance("SHA-256").digest(bytes);
      for(int i=0;i<d.length;i++) if((d[i]&255)!=Integer.parseInt(hash.substring(2*i,2*i+2),16)) throw new IOException("Unexpected US retail data; trial not enabled.");
    } catch(NoSuchAlgorithmException e){throw new IOException(e);}
  }
  static byte[] read(File f,int size) throws IOException {
    if(f.length()!=size) throw new IOException("Unexpected size: "+f.getName());
    byte[] b=new byte[size];try(DataInputStream in=new DataInputStream(new FileInputStream(f))){in.readFully(b);}return b;
  }
  static void patch(byte[][] rooms,byte[] esl) throws IOException {
    verify(esl,"dacf7a2ce08902e16ea8a5fa50bedff48853f40a8ddf4c4f9637107dae66382e");
    int[] records={3353776,4964272,3973004,4683720};byte[][] arrivals=new byte[4][19];
    for(int i=0;i<4;i++) {verify(rooms[i],STOCK[i]);System.arraycopy(rooms[i],records[i]+0x5c,arrivals[i],0,18);arrivals[i][18]=rooms[i][records[i]+0x74];}
    // Swap complete arrival payloads, not source trigger flags. Room order is A,B,C,D.
    for(int i=0;i<4;i++){System.arraycopy(arrivals[3-i],0,rooms[i],records[i]+0x5c,18);rooms[i][records[i]+0x74]=arrivals[3-i][18];}
    rooms[0][3832229]=1;rooms[0][3832385]=0x57;esl[0x27*32+2]=4;
    for(int i=0;i<4;i++) verify(rooms[i],RESULT[i]);
  }
  static File marker(File root){return new File(root,"randomizer/r100-threeway/emleon00.esl");}
  interface Decoder {String decode(String source,String output) throws IOException;}
  static String toggle(File root,Decoder decoder) throws IOException {
    File marker=marker(root);if(marker.exists()) {if(!marker.delete())throw new IOException("Cannot disable trial");return "Trial off. Your usual collection returns on the next game start.";}
    File dir=new File(marker.getParentFile(),"st1");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot prepare trial folder");
    byte[] esl=read(new File(root,"disc1/files/etc/emleon00.esl"),8160);byte[][] rooms=new byte[4][];
    for(int i=0;i<4;i++){File out=new File(dir,ROOMS[i]+".das.raw");decoder.decode(new File(root,"disc1/files/St1/"+ROOMS[i]+".das").getPath(),out.getPath());rooms[i]=read(out,SIZE[i]);}
    patch(rooms,esl);
    for(int i=0;i<4;i++){File out=new File(dir,ROOMS[i]+".das.raw");write(out,rooms[i]);verify(read(out,SIZE[i]),RESULT[i]);}
    File tmp=File.createTempFile("enemy-",".tmp",dir);try {write(tmp,esl);if(!tmp.renameTo(marker))throw new IOException("Cannot activate trial");}finally{if(tmp.exists())tmp.delete();}
    return "Seed 100 trial on. Start a new game with in-game item and enemy mods off. Check the ambush, both crow drops and the forest exit and return.";
  }
  static void write(File f,byte[] b)throws IOException{try(FileOutputStream out=new FileOutputStream(f)){out.write(b);out.getFD().sync();}}
}
