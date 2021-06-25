package org.apache.maven;

public class TransferObject {
	String message;
	
	public TransferObject() {
		this(null);
	}
	
	public TransferObject(String message) {
		this.message = message;
	}
	
	public String getMessage() {
		return this.message;
	}
}
