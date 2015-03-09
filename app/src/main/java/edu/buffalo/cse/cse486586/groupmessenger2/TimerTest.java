package edu.buffalo.cse.cse486586.groupmessenger2;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by Chaitanya on 3/9/15.
 */
public class TimerTest {
	public static void main(String[] args) {
		TestClass.main(null);
	}
}

class TestClass {
	public long myLong = 5000;

	public static void main(String[] args) {
		final TestClass test = new TestClass();

		Timer timer = new Timer();
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				test.doStuff();
			}
		}, 0, test.myLong);
	}

	public void doStuff() {
		System.out.println("doing stuff");
	}
}