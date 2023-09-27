package upstart.json;

import com.fasterxml.jackson.databind.ser.PropertyFilter;

public interface JsonPropertyFilter extends PropertyFilter {
  String id();
}
