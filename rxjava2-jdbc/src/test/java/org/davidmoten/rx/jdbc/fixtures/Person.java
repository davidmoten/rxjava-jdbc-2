package org.davidmoten.rx.jdbc.fixtures;

import java.util.Objects;

public class Person {
    public final String firstName;
    public final String lastName;

    public Person(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Person test = (Person) o;
        return Objects.equals(firstName, test.firstName) &&
                Objects.equals(lastName, test.lastName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstName, lastName);
    }
}
