# Format String

Format-String in compile-time

## Installation

Currently, only JDK 1.8 is supported

```` xml
<dependency>
    <groupId>io.github.qaralotte</groupId>
    <artifactId>fstring</artifactId>
    <version>1.0.0</version>
</dependency>
````

## Usage

Add *@FormatString* to any **Class** or **Method**
```` java
@FormatString
public class MyTestClass
...

````

or

```` java
@FormatString
private void myTestFunc()
...
````

Now you can use format string

```` java
// output -> Hello World
String world = "World";
System.out.println("Hello {world}");

// output -> 51
int a = 3, b = 2;
System.out.println("{s + b}{s - b}");

// output -> The second element of the array is: 456
String[] arr = {"123", "456", "789"};
System.out.println("The second element of the array is: {arr[1]}");

// output -> wow, fstring in java
public String getTitle(String s) {
    return s;
}
System.out.println("wow, {this.getTitle(\\"fstring\\") in java}");
System.out.println("wow, {this.getTitle(`fstring`) in java}"); // use ` instead of "

// output -> b is 1
int b = 1;
System.out.println("{b == 1 ? `b is 1`: `b not is 1`}");

// output -> hello {`579-333`} world
@FormatString`
public String myTestFunc(int a, int b) {
    return "{a + b}{b - b}";
}
System.out.println("hello {`{myTestFunc(123, 456)}`} world");
````

## Dependencies
[JavaParser](https://github.com/javaparser/javaparser)

## License

Format-String complies is released under the [Apache](https://github.com/qaralotte/fstring/blob/master/LICENSE.APACHE)

