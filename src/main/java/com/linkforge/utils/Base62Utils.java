package com.linkforge.utils;


//用于编写base62进制的工具类
//短码（如 a8Ks9d）本质上就是把数据库的递增 id 转成 62 进制字符串（字符集：0-9, a-z, A-Z）。
public class Base62Utils {
    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SCALE = CHARS.length();

    //十进制转62进制
    public static String encode(long num){
        if(num == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while(num>0){

            sb.append(CHARS.charAt((int)num%SCALE));
            num/=SCALE;
        }
        return sb.reverse().toString();
    }


}
