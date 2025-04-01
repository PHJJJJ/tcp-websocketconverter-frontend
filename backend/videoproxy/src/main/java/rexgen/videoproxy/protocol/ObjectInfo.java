package rexgen.videoproxy.protocol;

import java.util.ArrayList;
import java.util.List;

public class ObjectInfo {
    private int index;
    private RwcVaEnums.ObjectType type;
    private float detectionScore;

    private float classScore;
    private float x;
    private float y;
    private float width;
    private float height;
    private List<Short> attributes = new ArrayList<>();

    // Getters and setters
    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public RwcVaEnums.ObjectType getType() {
        return type;
    }

    public void setType(RwcVaEnums.ObjectType type) {
        this.type = type;
    }

    public float getDetectionScore() {
        return detectionScore;
    }

    public void setDetectionScore(float detectionScore) {
        this.detectionScore = detectionScore;
    }

    public float getClassScore() {
        return classScore;
    }

    public void setClassScore(float classScore) {
        this.classScore = classScore;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public float getHeight() {
        return height;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public List<Short> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<Short> attributes) {
        this.attributes = attributes;
    }

    public void addAttribute(short attribute) {
        this.attributes.add(attribute);
    }
}
