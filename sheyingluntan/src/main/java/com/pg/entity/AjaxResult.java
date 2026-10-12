package com.pg.entity;

import java.util.HashMap;

/*
AjaxResult 继承了Java集合框架中的 HashMap。这意味着 AjaxResult 本质上就是一个键值对集合。
由于Spring MVC等框架在返回 Map 类型时，会自动将其序列化为JSON对象，
因此继承 HashMap 可以让这个类天然具备转换为JSON的能力，无需额外引入Jackson或Gson等JSON库的注解。
http 请求的返回数据格式
code 错误码
msg 错误信息
data 结果集数组
total 结果集长度
 */
public class AjaxResult extends HashMap<String, Object> {
    //无参构造函数
    public AjaxResult()
    {

    }

    @Override
    //链式调用
    public AjaxResult put(String key, Object value) {
        super.put(key, value);
        return this;
    }
    //----------------------成功的返回

    public static AjaxResult success()
    {
        AjaxResult json=new AjaxResult();
        json.put("code",200);
        json.put("msg","操作成功");
        json.put("data",null);
        json.put("total",0);
        return json;
    }

    public static AjaxResult success(String msg)
    {
        AjaxResult json=new AjaxResult();
        json.put("code",200);
        json.put("msg",msg);
        json.put("data",null);
        json.put("total",0);
        return json;
    }

    public static AjaxResult success(long total,Object data)
    {
        AjaxResult json=new AjaxResult();
        json.put("code",200);
        json.put("msg","操作成功！");
        json.put("data",data);
        json.put("total",total);
        return json;
    }
    //------------------------失败------
    public static AjaxResult error()
    {
        AjaxResult json=new AjaxResult();
        json.put("code",500);
        json.put("msg","操作失败！");
        json.put("data",null);
        json.put("total",0);
        return json;
    }

    public static AjaxResult error(int code ,String msg)
    {
        AjaxResult json=new AjaxResult();
        json.put("code",code);
        json.put("msg",msg);
        json.put("data",null);
        json.put("total",0);
        return json;
    }

    public static AjaxResult success(Object data) {
        AjaxResult json = new AjaxResult();
        json.put("code", 200);
        json.put("msg", "操作成功");
        json.put("data", data);
        json.put("total", 1);  // 或 0，根据情况
        return json;
    }

}
