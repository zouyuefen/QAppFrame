package plat.frame.api.controller;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import plat.frame.api.APIEntity;
import plat.frame.api.APIReleaseInfo;
import plat.frame.api.annonation.APIDefiner;
import plat.frame.api.annonation.MethodDefiner;
import plat.frame.app.impl.BeanParser;
import plat.frame.app.impl.TargetSearcher;
import plat.frame.app.impl.URLMapper;
import plat.frame.component.QConfig;
import plat.tools.JsonCoder;
import plat.tools.PermKeeper;
import plat.tools.StringUtil;

@Controller
public class APIEntry extends BeanParser
{
	private static final String exampleMethod = "showExamqle";				//实例方法名字.
	
	@Autowired
	private QConfig qconf;
	
	@RequestMapping( value="/api/list/test.api" )
	@ResponseBody
	public String testapi( HttpServletRequest request )
	{
		return "api succeed.";
	}

	/**
	 * 按照功能查询所有发布接口.
	 * @param request
	 * @param module
	 * @return
	 * @throws NoSuchFieldException 
	 */
	@RequestMapping( value="/api/list/{module}.api",method=RequestMethod.GET)
	public String queryAllApiInfoByTransModel( HttpServletRequest request, @PathVariable String module ) throws NoSuchFieldException
	{
		String fullName = qconf.getApiPackage()+"."+module;
		try
		{
			Class<?> apiClass = Class.forName(fullName);
			Object obj = apiClass.newInstance();
			
			//模块名字.
			Field fdModule = apiClass.getDeclaredField("moduleName");
			String moduleName = (String)fdModule.get(obj);
			
			//API信息
			Field fdApis = apiClass.getDeclaredField("apiInfos");
			String[] apiInfos = (String[])fdApis.get(obj);
			
			//转换方法.
			Method mtdList = apiClass.getMethod("queryAllApiInfos",String[].class);
			List<APIReleaseInfo> apiList = (List<APIReleaseInfo>) mtdList.invoke(obj,new Object[]{apiInfos});
			
			request.setAttribute("moduleName",moduleName);
			request.setAttribute("apiList", apiList);
			request.setAttribute("hostUrl", qconf.getApiHostURL());
			
			return "/api/allAPIs";
		}
		catch (ClassNotFoundException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return "fail";
	}

	/******
	 * 显示某个接口的信息.
	 */
	@RequestMapping(value="/**/*.api",method=RequestMethod.GET)
//	@ResponseBody
	public String querySpecApiInfo( HttpServletRequest request )
	{	
		logger.info("__APP_RECV_api");
		
		//解析URL
		URLMapper urlMapper = new URLMapper(qconf.getAppName(), qconf.getTransPrefix());
		urlMapper.doParse(request.getRequestURI());

		if( !callAPIMain(request,urlMapper) )
		{
			return "fail";
		}
		
		return "/api/APITable";
	}
	
	/**
	 * 主方法.
	 * @param request
	 * @param module
	 * @param clazz
	 * @param method
	 * @return
	 */
	private boolean callAPIMain( HttpServletRequest request, URLMapper mapper )
	{
		//不对生产开放.
		if ( !PermKeeper.isTest() )
		{
			return false;
		}
		
		APIEntity apiEntity = null;
		try
		{
			apiEntity = convertApiEntity( mapper );
		}
		catch (ClassNotFoundException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		logger.info("__RET_MSG"+JsonCoder.toJsonString(apiEntity));
		
		//返回实体给JSP页面.
		request.setAttribute("apiEntity", apiEntity);
		
		//返回主机地址
		request.setAttribute("hostURL", qconf.getApiHostURL() );
		
		return true;
	}
	
	/**
	 * 从注解获取参数信息.
	 * @param targetClass
	 * @param methodName
	 * @return
	 * @throws NoSuchMethodException 
	 * @throws ClassNotFoundException 
	 */
	private APIEntity convertApiEntity( URLMapper mapper ) throws NoSuchMethodException, ClassNotFoundException
	{
		TargetSearcher tsearcher = TargetSearcher.getInstance();
		APIEntity entity = new APIEntity();
		
		//获取方法描述.
		Method targetMethod = tsearcher.findTargetMethod(mapper);
		MethodDefiner mtdef = targetMethod.getAnnotation(MethodDefiner.class);
		if ( mtdef != null )
		{
//			entity.setName(mtdef.name());
//			entity.setDesc(StringUtil.concat(mtdef.desc(), "\n"));
		}

		//获取版本以及变更描述.
		APIDefiner apidef = targetMethod.getAnnotation(APIDefiner.class);
		if ( apidef != null )
		{
			entity.setName(apidef.name());
			entity.setDesc(StringUtil.concat(apidef.desc(), "\n"));
			entity.setApiVersion(apidef.version());
			entity.setApiUpdates(apidef.updates());
		}
		
		Class<?>[] apiClazz = tsearcher.parseTargetParas(targetMethod);
		for ( int i = 0; i < apiClazz.length; ++i )
		{
			Class<?> tmpClz = apiClazz[i];
			
			logger.info("__CLASS PARSE:"+tmpClz.getSimpleName());
			
			//存放用于生成下载文件内容.
			entity.addBean(tmpClz.getSimpleName(), tmpClz.getName());
			
			//解析请求报文结构.
			if( tmpClz.getSimpleName().startsWith("Req") )
			{
				entity.setReqBeanInfo(doParseBeanInfo(tmpClz,entity));
//				entity.setReqExample(ETools.toJsonString(generateExampleObj(tmpClz)));
			}
			else
			//解析响应报文.
//			else if ( tmpClz.getSimpleName().startsWith("Rsp") )
			{
				entity.setRspBeanInfo(doParseBeanInfo(tmpClz,entity));
//				entity.setRspExample(ETools.toJsonString(generateExampleObj(tmpClz)));
			}
		}
		
		return entity;
	}
	
	private Object generateExampleObj( Class<?> clazz )
	{
		Method mtd;
		try {
			mtd = clazz.getMethod(exampleMethod, null );
			if ( mtd != null )
			{
				return mtd.invoke(clazz.newInstance(), null);
			}
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return null;
	}
}