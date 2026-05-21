package com.collabdoc.ot;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class OperationalTransform {

    public OTOperation transform(OTOperation op, OTOperation appliedOp) {
        if (op.getType() == OTOperation.Type.INSERT && appliedOp.getType() == OTOperation.Type.INSERT) {
            return transformInsertInsert(op, appliedOp);
        } else if (op.getType() == OTOperation.Type.INSERT && appliedOp.getType() == OTOperation.Type.DELETE) {
            return transformInsertDelete(op, appliedOp);
        } else if (op.getType() == OTOperation.Type.DELETE && appliedOp.getType() == OTOperation.Type.INSERT) {
            return transformDeleteInsert(op, appliedOp);
        } else {
            return transformDeleteDelete(op, appliedOp);
        }
    }

    private OTOperation transformInsertInsert(OTOperation op, OTOperation appliedOp) {
        int newPos = op.getPosition();
        if (op.getPosition() > appliedOp.getPosition() ||
            (op.getPosition() == appliedOp.getPosition() && op.hashCode() > appliedOp.hashCode())) {
            newPos = op.getPosition() + appliedOp.getEffectLength();
        }
        return OTOperation.insert(newPos, op.getText());
    }

    private OTOperation transformInsertDelete(OTOperation op, OTOperation appliedOp) {
        int newPos = op.getPosition();
        if (op.getPosition() > appliedOp.getPosition() + appliedOp.getLength()) {
            newPos = op.getPosition() - appliedOp.getLength();
        } else if (op.getPosition() > appliedOp.getPosition()) {
            newPos = appliedOp.getPosition();
        }
        return OTOperation.insert(newPos, op.getText());
    }

    private OTOperation transformDeleteInsert(OTOperation op, OTOperation appliedOp) {
        int newPos = op.getPosition();
        if (appliedOp.getPosition() <= op.getPosition()) {
            newPos = op.getPosition() + appliedOp.getEffectLength();
        }
        return OTOperation.delete(newPos, op.getLength());
    }

    private OTOperation transformDeleteDelete(OTOperation op, OTOperation appliedOp) {
        int newPos = op.getPosition();
        int newLen = op.getLength();

        if (appliedOp.getPosition() + appliedOp.getLength() <= op.getPosition()) {
            newPos = op.getPosition() - appliedOp.getLength();
        } else if (appliedOp.getPosition() >= op.getPosition() + op.getLength()) {
        } else {
            int overlapStart = Math.max(op.getPosition(), appliedOp.getPosition());
            int overlapEnd = Math.min(op.getPosition() + op.getLength(),
                                       appliedOp.getPosition() + appliedOp.getLength());
            int overlapLen = Math.max(0, overlapEnd - overlapStart);
            newLen = op.getLength() - overlapLen;
            newPos = Math.min(op.getPosition(), appliedOp.getPosition());
            if (newLen <= 0) {
                return null;
            }
        }
        return OTOperation.delete(newPos, newLen);
    }

    public List<OTOperation> transformAgainstHistory(OTOperation op, List<OTOperation> history) {
        OTOperation transformed = op;
        List<OTOperation> result = new ArrayList<>();
        for (OTOperation applied : history) {
            if (applied == transformed) continue;
            transformed = transform(transformed, applied);
            if (transformed != null) {
                result.add(transformed);
            } else {
                return List.of();
            }
        }
        return result;
    }

    public String applyOperation(String content, OTOperation op) {
        StringBuilder sb = new StringBuilder(content);
        if (op.getType() == OTOperation.Type.INSERT) {
            int pos = Math.min(op.getPosition(), sb.length());
            sb.insert(pos, op.getText());
        } else {
            int pos = Math.min(op.getPosition(), sb.length());
            int end = Math.min(pos + op.getLength(), sb.length());
            sb.delete(pos, end);
        }
        return sb.toString();
    }
}
