package com.pg.myutils;


import org.springframework.cglib.beans.BeanMap;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据处理的工具**/
public class dataUtil {


    //---将对象转化成map
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
