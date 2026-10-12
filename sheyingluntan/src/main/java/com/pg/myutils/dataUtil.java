package com.pg.myutils;


import org.springframework.cglib.beans.BeanMap;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据处理的工具**/
public class dataUtil {


    //---静态方法 ObjToMap，将一个Java对象（POJO）的属性和属性值提取出来，
    // 转换成一个 Map<String, Object> 结构。
    // 其中，Map的Key是对象的属性名，Value是对应的属性值。
    public static Map<String,Object> ObjToMap(Object object)
    {

        Map<String,Object> dataMap=new HashMap<>();
        BeanMap beanMap=BeanMap.create(object);
        for(Object key:beanMap.keySet())
        {
            dataMap.put(key+"",beanMap.get(key));
        }

        return dataMap;
    }

}
