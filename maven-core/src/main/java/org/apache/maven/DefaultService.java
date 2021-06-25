package org.apache.maven;

import org.codehaus.plexus.component.annotations.Component;

/**
 * @author Jason van Zyl
 */
@Component(role = Service.class)
public class DefaultService implements Service {
	public void doThing(TransferObject tObj) {
		System.out.println(String.format("Service %s start!", this.getClass().getName().toString()));
		System.out.println(String.format("Message from tranfer object: %s", tObj.getMessage()));
	}
}
